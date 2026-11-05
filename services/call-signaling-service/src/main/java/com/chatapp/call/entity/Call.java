package com.chatapp.call.entity;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.chatapp.call.enums.CallStatus;
import com.chatapp.call.enums.CallType;
import com.chatapp.call.enums.EndReason;
import com.chatapp.call.exception.ConflictException;
import com.chatapp.call.exception.ForbiddenException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/**
 * One call between two people, and the rules for how its state may change (a small state machine):
 *
 *   RINGING --accept--> ACCEPTED --hang up / connection lost--> ENDED
 *   RINGING --reject--> REJECTED      RINGING --caller hangs up / nobody answers--> MISSED
 *   BUSY: the callee was in another call, so nothing ever rang.
 *
 * The methods below check that a change is allowed and then make it. They do not save anything: the service does
 * that inside a transaction, after locking the row, so two changes at the same moment cannot both win.
 * Who may do what (only the callee accepts, only the call devices signal) is checked here too.
 */
@Entity
@Table(name = "calls")
@Getter
@NoArgsConstructor
public class Call {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "caller_id", nullable = false)
    private UUID callerId;

    @Column(name = "callee_id", nullable = false)
    private UUID calleeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CallType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CallStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 30)
    private EndReason endReason;

    @Column(name = "caller_device_id", nullable = false)
    private UUID callerDeviceId;

    @Column(name = "callee_device_id")
    private UUID calleeDeviceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "last_caller_ping_at")
    private Instant lastCallerPingAt;

    @Column(name = "last_callee_ping_at")
    private Instant lastCalleePingAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** A new call starts ringing. The caller's device is the one that started it. */
    public Call(UUID callerId, UUID callerDeviceId, UUID calleeId, CallType type, Instant now) {
        this.callerId = callerId;
        this.callerDeviceId = callerDeviceId;
        this.calleeId = calleeId;
        this.type = type;
        this.status = CallStatus.RINGING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    // ------------------------------------------------------------------ questions

    public boolean isParticipant(UUID userId) {
        return callerId.equals(userId) || calleeId.equals(userId);
    }

    public boolean isFinal() {
        return status.isFinal();
    }

    /** The other person of the call, seen from userId. */
    public UUID otherUserOf(UUID userId) {
        return callerId.equals(userId) ? calleeId : callerId;
    }

    /** Talk time in whole seconds: only for calls that were answered and have ended, otherwise 0. */
    public long durationSeconds() {
        if (status != CallStatus.ENDED || answeredAt == null || endedAt == null) {
            return 0;
        }
        return Duration.between(answeredAt, endedAt).getSeconds();
    }

    // ------------------------------------------------------------------ changes

    /** The callee picks up on one of their devices. That device becomes the callee's call device. */
    public void accept(UUID userId, UUID deviceId, Instant now) {
        requireCallee(userId);
        if (status != CallStatus.RINGING) {
            throw new ConflictException("This call is no longer ringing", "CALL_NOT_RINGING");
        }
        status = CallStatus.ACCEPTED;
        answeredAt = now;
        calleeDeviceId = deviceId;
        // both sides start "alive" now; from here on each side pings every few seconds
        lastCallerPingAt = now;
        lastCalleePingAt = now;
        updatedAt = now;
    }

    /** The callee declines. */
    public void reject(UUID userId, Instant now) {
        requireCallee(userId);
        if (status != CallStatus.RINGING) {
            throw new ConflictException("This call is no longer ringing", "CALL_NOT_RINGING");
        }
        finish(CallStatus.REJECTED, EndReason.DECLINED, now);
    }

    /**
     * Somebody presses "hang up". While ringing this means "cancel" (caller) or "decline" (callee); during the call
     * it ends it. Returns false when the call had already ended (nothing changes: hanging up twice is fine).
     */
    public boolean hangUp(UUID userId, Instant now) {
        if (isFinal()) {
            return false;
        }
        if (status == CallStatus.RINGING) {
            if (callerId.equals(userId)) {
                finish(CallStatus.MISSED, EndReason.CANCELLED, now);
            } else {
                finish(CallStatus.REJECTED, EndReason.DECLINED, now);
            }
        } else {
            finish(CallStatus.ENDED, EndReason.HANGUP, now);
        }
        return true;
    }

    /** The callee was in another call when this one started: it is final at once. */
    public void markBusy(Instant now) {
        finish(CallStatus.BUSY, EndReason.BUSY, now);
    }

    /** The time-out job: nobody answered. Returns false if the call is not ringing (any more) or not old enough. */
    public boolean expireIfRingingSince(Instant cutoff, Instant now) {
        if (status != CallStatus.RINGING || !createdAt.isBefore(cutoff)) {
            return false;
        }
        finish(CallStatus.MISSED, EndReason.NO_ANSWER, now);
        return true;
    }

    /** The time-out job: one side stopped pinging. Returns false if both sides pinged after the cutoff. */
    public boolean endIfSilentSince(Instant cutoff, Instant now) {
        if (status != CallStatus.ACCEPTED) {
            return false;
        }
        boolean callerSilent = lastCallerPingAt == null || lastCallerPingAt.isBefore(cutoff);
        boolean calleeSilent = lastCalleePingAt == null || lastCalleePingAt.isBefore(cutoff);
        if (!callerSilent && !calleeSilent) {
            return false;
        }
        finish(CallStatus.ENDED, EndReason.CONNECTION_LOST, now);
        return true;
    }

    /**
     * Checks that userId may send signals / pings for this call from deviceId: the call must be ACCEPTED and the
     * device must be that person's call device. Returns the OTHER side's call device (where a signal goes).
     */
    public UUID requireCallDevice(UUID userId, UUID deviceId) {
        if (status != CallStatus.ACCEPTED) {
            throw new ConflictException("This call is not in progress", "CALL_NOT_ACTIVE");
        }
        boolean isCaller = callerId.equals(userId);
        UUID myDevice = isCaller ? callerDeviceId : calleeDeviceId;
        if (!deviceId.equals(myDevice)) {
            throw new ForbiddenException("This call is running on another of your devices", "NOT_CALL_DEVICE");
        }
        return isCaller ? calleeDeviceId : callerDeviceId;
    }

    /** "My side is still there." Only from my call device during an accepted call. */
    public void ping(UUID userId, UUID deviceId, Instant now) {
        requireCallDevice(userId, deviceId);
        if (callerId.equals(userId)) {
            lastCallerPingAt = now;
        } else {
            lastCalleePingAt = now;
        }
        updatedAt = now;
    }

    // ------------------------------------------------------------------ helpers

    private void requireCallee(UUID userId) {
        if (!calleeId.equals(userId)) {
            throw new ForbiddenException("Only the person who is called can do this", "NOT_CALLEE");
        }
    }

    private void finish(CallStatus finalStatus, EndReason reason, Instant now) {
        status = finalStatus;
        endReason = reason;
        endedAt = now;
        updatedAt = now;
    }
}
