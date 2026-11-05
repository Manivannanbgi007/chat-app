package com.chatapp.call.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import com.chatapp.call.client.UserClient;
import com.chatapp.call.dto.request.SignalRequest;
import com.chatapp.call.dto.request.StartCallRequest;
import com.chatapp.call.dto.response.CallResponse;
import com.chatapp.call.dto.response.CallSessionResponse;
import com.chatapp.call.dto.response.PageResponse;
import com.chatapp.call.dto.response.PublicProfileResponse;
import com.chatapp.call.entity.Call;
import com.chatapp.call.enums.CallStatus;
import com.chatapp.call.enums.CallType;
import com.chatapp.call.exception.BadRequestException;
import com.chatapp.call.exception.ConflictException;
import com.chatapp.call.exception.ForbiddenException;
import com.chatapp.call.exception.ResourceNotFoundException;
import com.chatapp.call.kafka.producer.CallEndedPayload;
import com.chatapp.call.kafka.producer.OutboxService;
import com.chatapp.call.realtime.CallEventPublisher;
import com.chatapp.call.repository.CallRepository;
import com.chatapp.call.repository.CallSlotRepository;
import com.chatapp.call.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Everything that happens to a call: start, accept, reject, hang up, signal, ping, the time-outs, and reading.
 *
 * The pattern of every change:
 *  1. questions to user-service first (no database transaction is open while we wait for another service)
 *  2. ONE transaction: lock the call row, let the Call entity check and apply the change; if the call is now
 *     final, free both busy slots and queue a CallEndedEvent (outbox) in the same transaction
 *  3. after the commit: tell the browsers (live events through Redis), each participant with their own view
 * The rules themselves (who may do what in which state) live in the Call entity.
 */
@Service
public class CallService {

    private static final Logger log = LoggerFactory.getLogger(CallService.class);
    private static final int MAX_PAGE_SIZE = 50;
    private static final int CREATE_ATTEMPTS = 3;

    private final CallRepository callRepository;
    private final CallSlotRepository slotRepository;
    private final UserClient userClient;
    private final CallViewService views;
    private final TurnCredentialService turnCredentials;
    private final CallEventPublisher events;
    private final OutboxService outboxService;
    private final TransactionTemplate transactionTemplate;

    public CallService(CallRepository callRepository, CallSlotRepository slotRepository, UserClient userClient,
                       CallViewService views, TurnCredentialService turnCredentials, CallEventPublisher events,
                       OutboxService outboxService, TransactionTemplate transactionTemplate) {
        this.callRepository = callRepository;
        this.slotRepository = slotRepository;
        this.userClient = userClient;
        this.views = views;
        this.turnCredentials = turnCredentials;
        this.events = events;
        this.outboxService = outboxService;
        this.transactionTemplate = transactionTemplate;
    }

    // ------------------------------------------------------------------- start

    /**
     * Starts a call from this device. The answer is RINGING (the callee's devices ring now) or BUSY (the callee is
     * in another call: nothing rings). 409 ALREADY_IN_CALL when I am in a call myself.
     */
    public CallSessionResponse start(UUID me, UUID myDevice, StartCallRequest request) {
        UUID callee = request.calleeId();
        if (callee.equals(me)) {
            throw new BadRequestException("You cannot call yourself");
        }
        // 1. user-service: blocks first (same answer whoever blocked whom), then "does this person exist for me?"
        if (userClient.blockedEitherWay(me, callee)) {
            throw new ForbiddenException("You cannot call this user", "CALL_NOT_ALLOWED");
        }
        PublicProfileResponse calleeProfile = userClient.findProfiles(me, List.of(callee)).get(callee);
        if (calleeProfile == null) {
            throw new ResourceNotFoundException("User not found");
        }

        // 2. the call row and both busy slots, in one transaction
        Call call = createWithRetry(me, myDevice, callee, request.type());
        CallResponse myView = CallResponse.of(call, me, calleeProfile);
        if (call.getStatus() != CallStatus.RINGING) {
            log.info("Call {} could not ring: the callee is busy", call.getId());
            return new CallSessionResponse(myView, List.of());
        }

        // 3. after the commit: the callee's devices start ringing
        log.info("Call {} is ringing", call.getId());
        events.incoming(callee, views.viewFor(call, callee));
        return new CallSessionResponse(myView, turnCredentials.iceServersFor(me));
    }

    /** MySQL may pick our transaction as a deadlock victim when many calls start at once: then simply try again. */
    private Call createWithRetry(UUID me, UUID myDevice, UUID callee, CallType type) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(status -> createInTransaction(me, myDevice, callee, type));
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= CREATE_ATTEMPTS) {
                    throw e;
                }
                log.debug("Starting a call collided with another one, trying again (attempt {})", attempt);
            }
        }
    }

    private Call createInTransaction(UUID me, UUID myDevice, UUID callee, CallType type) {
        Instant now = Times.now();
        // saveAndFlush: the call row must exist before the slots point to it (foreign key)
        Call call = callRepository.saveAndFlush(new Call(me, myDevice, callee, type, now));

        // Take both slots, always in the same order (by id text), so two transactions never wait for each other in
        // a circle (A calls B while B calls A).
        boolean meFirst = me.toString().compareTo(callee.toString()) < 0;
        boolean firstTaken = takeSlot(meFirst ? me : callee, call, now);
        boolean secondTaken = takeSlot(meFirst ? callee : me, call, now);
        boolean callerFree = meFirst ? firstTaken : secondTaken;
        boolean calleeFree = meFirst ? secondTaken : firstTaken;

        if (!callerFree) {
            // rolls back the whole transaction: the call row and the callee's slot disappear again
            throw new ConflictException("You are already in a call", "ALREADY_IN_CALL");
        }
        if (!calleeFree) {
            call.markBusy(now);
            finishInTransaction(call);      // frees my slot again and records the BUSY call
        }
        return call;
    }

    private boolean takeSlot(UUID userId, Call call, Instant now) {
        return slotRepository.takeSlot(userId.toString(), call.getId().toString(), now) == 1;
    }

    // ----------------------------------------------------------- accept / reject / end

    /** The callee answers on this device. 409 CALL_NOT_RINGING when another device was faster (or it ended). */
    public CallSessionResponse accept(UUID me, UUID myDevice, UUID callId) {
        Call call = changeCall(callId, me, locked -> {
            locked.accept(me, myDevice, Times.now());
            return true;
        }).call();
        log.info("Call {} was accepted", call.getId());

        CallResponse myView = views.viewFor(call, me);
        events.accepted(call.getCallerId(), views.viewFor(call, call.getCallerId()), call.getCalleeDeviceId());
        events.accepted(me, myView, call.getCalleeDeviceId());   // my other devices stop ringing
        return new CallSessionResponse(myView, turnCredentials.iceServersFor(me));
    }

    /** The callee declines. */
    public CallResponse reject(UUID me, UUID callId) {
        Call call = changeCall(callId, me, locked -> {
            locked.reject(me, Times.now());
            return true;
        }).call();
        log.info("Call {} was declined", call.getId());
        return publishEnded(call, me);
    }

    /** Hang up (or cancel / decline while ringing). Repeating it on an ended call changes nothing. */
    public CallResponse end(UUID me, UUID callId) {
        Change change = changeCall(callId, me, locked -> locked.hangUp(me, Times.now()));
        if (!change.changed()) {
            return views.viewFor(change.call(), me);
        }
        log.info("Call {} ended: {} / {}", callId, change.call().getStatus(), change.call().getEndReason());
        return publishEnded(change.call(), me);
    }

    // ------------------------------------------------------------- signal / ping

    /** Relays a WebRTC set-up message to the other person's call device. Nothing is stored. */
    public void signal(UUID me, UUID myDevice, UUID callId, SignalRequest request) {
        Call call = findMyCall(callId, me);
        UUID toDevice = call.requireCallDevice(me, myDevice);
        events.signal(call.otherUserOf(me), new CallEventPublisher.SignalData(call.getId(), me, toDevice,
                request.type(), request.sdp(), request.candidates()));
    }

    /** "My side of the call is still alive" (every 10 s from both call devices). */
    public void ping(UUID me, UUID myDevice, UUID callId) {
        changeCall(callId, me, locked -> {
            locked.ping(me, myDevice, Times.now());
            return true;
        });
    }

    // ---------------------------------------------------------------- time-outs

    /** Used by the time-out job: "nobody answered". Returns true if this call was ended now. */
    public boolean expireRinging(UUID callId, Instant cutoff) {
        return finishByJob(callId, call -> call.expireIfRingingSince(cutoff, Times.now()));
    }

    /** Used by the time-out job: "one side stopped pinging". Returns true if this call was ended now. */
    public boolean endSilent(UUID callId, Instant cutoff) {
        return finishByJob(callId, call -> call.endIfSilentSince(cutoff, Times.now()));
    }

    private boolean finishByJob(UUID callId, Predicate<Call> change) {
        Call ended = transactionTemplate.execute(status -> {
            // SKIP LOCKED: if another instance (or a user request) holds this call right now, leave it alone;
            // the next run looks again.
            Optional<Call> locked = callRepository.findByIdForUpdateSkipLocked(callId.toString());
            if (locked.isEmpty() || !change.test(locked.get())) {
                return null;
            }
            finishInTransaction(locked.get());
            return locked.get();
        });
        if (ended == null) {
            return false;
        }
        log.info("Call {} ended by the time-out job: {} / {}", callId, ended.getStatus(), ended.getEndReason());
        publishEnded(ended, ended.getCallerId());
        return true;
    }

    // -------------------------------------------------------------------- read

    /** My call that is ringing or in progress, or null. */
    public CallResponse active(UUID me) {
        return slotRepository.findById(me)
                .flatMap(slot -> callRepository.findById(slot.getCallId()))
                .filter(call -> !call.isFinal())
                .map(call -> views.viewFor(call, me))
                .orElse(null);
    }

    public PageResponse<CallResponse> history(UUID me, int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page must be 0 or more", "VALIDATION");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_PAGE_SIZE, "VALIDATION");
        }
        Page<Call> result = callRepository.findHistory(me, PageRequest.of(page, size));
        return new PageResponse<>(views.viewsFor(result.getContent(), me), page, size, result.getTotalElements(),
                result.hasNext());
    }

    /** One call. Unknown and "not mine" give the same 404, so nobody can probe for call ids. */
    public CallResponse get(UUID me, UUID callId) {
        return views.viewFor(findMyCall(callId, me), me);
    }

    // ------------------------------------------------------------------ helpers

    private record Change(Call call, boolean changed) {
    }

    /**
     * Locks the call (404 unless I am a participant), applies the change, and if the call is now final frees the
     * slots and queues the event, all in one transaction. The change returns false when it changed nothing.
     */
    private Change changeCall(UUID callId, UUID me, Predicate<Call> change) {
        return transactionTemplate.execute(status -> {
            Call call = callRepository.findByIdForUpdate(callId)
                    .filter(found -> found.isParticipant(me))
                    .orElseThrow(() -> new ResourceNotFoundException("Call not found"));
            boolean changed = change.test(call);
            if (changed && call.isFinal()) {
                finishInTransaction(call);
            }
            return new Change(call, changed);
        });
    }

    /** The call just reached its final state: both people are free again, and the world is told (outbox). */
    private void finishInTransaction(Call call) {
        slotRepository.releaseSlots(call.getId());
        outboxService.queue(CallEndedPayload.EVENT_TYPE, call.getId(), CallEndedPayload.of(call));
    }

    /** CALL_ENDED to both participants (each with their own view); returns the view of `viewer`. */
    private CallResponse publishEnded(Call call, UUID viewer) {
        CallResponse callerView = views.viewFor(call, call.getCallerId());
        CallResponse calleeView = views.viewFor(call, call.getCalleeId());
        events.ended(call.getCallerId(), callerView);
        events.ended(call.getCalleeId(), calleeView);
        return viewer.equals(call.getCallerId()) ? callerView : calleeView;
    }

    private Call findMyCall(UUID callId, UUID me) {
        return callRepository.findById(callId)
                .filter(call -> call.isParticipant(me))
                .orElseThrow(() -> new ResourceNotFoundException("Call not found"));
    }
}
