package com.chatapp.call.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.chatapp.call.config.AppProperties;
import com.chatapp.call.repository.CallRepository;
import com.chatapp.call.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cleans up calls whose browsers went quiet, so nobody stays "busy" for ever:
 *  - RINGING longer than the ring time-out (30 s)            -> MISSED / NO_ANSWER
 *  - ACCEPTED and one side has not pinged for 30 s          -> ENDED / CONNECTION_LOST
 * It runs on every instance; each call is handled in its own transaction with FOR UPDATE SKIP LOCKED and the state is
 * checked again under the lock, so two instances (or a user hanging up at the same moment) never end a call twice.
 */
@Component
public class CallTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(CallTimeoutJob.class);
    private static final PageRequest BATCH = PageRequest.of(0, 100);

    private final CallRepository callRepository;
    private final CallService callService;
    private final AppProperties.Calls limits;

    public CallTimeoutJob(CallRepository callRepository, CallService callService, AppProperties properties) {
        this.callRepository = callRepository;
        this.callService = callService;
        this.limits = properties.calls();
    }

    @Scheduled(fixedDelayString = "${chatapp.calls.timeout-check-ms:1000}")
    public void run() {
        try {
            Instant now = Times.now();
            Instant ringCutoff = now.minusSeconds(limits.ringTimeoutSeconds());
            for (UUID callId : callRepository.findRingingStartedBefore(ringCutoff, BATCH)) {
                callService.expireRinging(callId, ringCutoff);
            }
            Instant pingCutoff = now.minusSeconds(limits.pingTimeoutSeconds());
            List<UUID> silent = callRepository.findAcceptedSilentSince(pingCutoff, BATCH);
            for (UUID callId : silent) {
                callService.endSilent(callId, pingCutoff);
            }
        } catch (RuntimeException e) {
            // never let one failure stop the schedule; the next run tries again
            log.warn("Call time-out check failed ({})", e.getClass().getSimpleName());
        }
    }
}
