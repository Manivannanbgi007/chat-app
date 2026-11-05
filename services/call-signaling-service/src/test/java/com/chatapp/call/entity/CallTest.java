package com.chatapp.call.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.call.enums.CallStatus;
import com.chatapp.call.enums.CallType;
import com.chatapp.call.enums.EndReason;
import com.chatapp.call.exception.ApiException;
import org.junit.jupiter.api.Test;

/** The call state machine, without Spring or a database. */
class CallTest {

    private final UUID caller = UUID.randomUUID();
    private final UUID callerDevice = UUID.randomUUID();
    private final UUID callee = UUID.randomUUID();
    private final UUID calleeDevice = UUID.randomUUID();
    private final Instant start = Instant.parse("2026-11-04T10:00:00Z");

    private Call ringing() {
        return new Call(caller, callerDevice, callee, CallType.VIDEO, start);
    }

    private Call accepted() {
        Call call = ringing();
        call.accept(callee, calleeDevice, start.plusSeconds(5));
        return call;
    }

    private static String errorCodeOf(Runnable action) {
        try {
            action.run();
        } catch (ApiException e) {
            return e.getErrorCode();
        }
        throw new AssertionError("expected an ApiException");
    }

    @Test
    void aNewCallRings() {
        Call call = ringing();
        assertThat(call.getStatus()).isEqualTo(CallStatus.RINGING);
        assertThat(call.isFinal()).isFalse();
        assertThat(call.isParticipant(caller)).isTrue();
        assertThat(call.isParticipant(callee)).isTrue();
        assertThat(call.isParticipant(UUID.randomUUID())).isFalse();
        assertThat(call.otherUserOf(caller)).isEqualTo(callee);
        assertThat(call.otherUserOf(callee)).isEqualTo(caller);
    }

    @Test
    void onlyTheCalleeCanAcceptAndOnlyWhileRinging() {
        Call call = ringing();
        assertThat(errorCodeOf(() -> call.accept(caller, callerDevice, start))).isEqualTo("NOT_CALLEE");

        call.accept(callee, calleeDevice, start.plusSeconds(5));
        assertThat(call.getStatus()).isEqualTo(CallStatus.ACCEPTED);
        assertThat(call.getAnsweredAt()).isEqualTo(start.plusSeconds(5));
        assertThat(call.getCalleeDeviceId()).isEqualTo(calleeDevice);
        assertThat(call.getLastCallerPingAt()).isEqualTo(start.plusSeconds(5));
        assertThat(call.getLastCalleePingAt()).isEqualTo(start.plusSeconds(5));

        assertThat(errorCodeOf(() -> call.accept(callee, UUID.randomUUID(), start))).isEqualTo("CALL_NOT_RINGING");
        assertThat(errorCodeOf(() -> call.reject(callee, start))).isEqualTo("CALL_NOT_RINGING");
    }

    @Test
    void rejectIsForTheCalleeOnly() {
        Call call = ringing();
        assertThat(errorCodeOf(() -> call.reject(caller, start))).isEqualTo("NOT_CALLEE");
        call.reject(callee, start.plusSeconds(2));
        assertThat(call.getStatus()).isEqualTo(CallStatus.REJECTED);
        assertThat(call.getEndReason()).isEqualTo(EndReason.DECLINED);
        assertThat(call.getEndedAt()).isEqualTo(start.plusSeconds(2));
    }

    @Test
    void hangingUpDependsOnWhoAndWhen() {
        Call cancelled = ringing();
        assertThat(cancelled.hangUp(caller, start)).isTrue();
        assertThat(cancelled.getStatus()).isEqualTo(CallStatus.MISSED);
        assertThat(cancelled.getEndReason()).isEqualTo(EndReason.CANCELLED);

        Call declined = ringing();
        declined.hangUp(callee, start);
        assertThat(declined.getStatus()).isEqualTo(CallStatus.REJECTED);
        assertThat(declined.getEndReason()).isEqualTo(EndReason.DECLINED);

        Call talked = accepted();
        talked.hangUp(callee, start.plusSeconds(65));
        assertThat(talked.getStatus()).isEqualTo(CallStatus.ENDED);
        assertThat(talked.getEndReason()).isEqualTo(EndReason.HANGUP);
        assertThat(talked.durationSeconds()).isEqualTo(60);

        // a final call never changes again
        assertThat(talked.hangUp(caller, start.plusSeconds(99))).isFalse();
        assertThat(talked.getEndedAt()).isEqualTo(start.plusSeconds(65));
    }

    @Test
    void onlyAnsweredCallsHaveADuration() {
        Call missed = ringing();
        missed.hangUp(caller, start.plusSeconds(20));
        assertThat(missed.durationSeconds()).isZero();
        assertThat(accepted().durationSeconds()).as("still running").isZero();
    }

    @Test
    void busyIsFinalAtOnce() {
        Call call = ringing();
        call.markBusy(start);
        assertThat(call.getStatus()).isEqualTo(CallStatus.BUSY);
        assertThat(call.getEndReason()).isEqualTo(EndReason.BUSY);
        assertThat(call.isFinal()).isTrue();
    }

    @Test
    void ringTimeOutOnlyHitsOldRingingCalls() {
        Call call = ringing();
        assertThat(call.expireIfRingingSince(start, start.plusSeconds(40))).as("created exactly at the cutoff").isFalse();
        assertThat(call.expireIfRingingSince(start.plusSeconds(1), start.plusSeconds(40))).isTrue();
        assertThat(call.getStatus()).isEqualTo(CallStatus.MISSED);
        assertThat(call.getEndReason()).isEqualTo(EndReason.NO_ANSWER);

        assertThat(accepted().expireIfRingingSince(start.plusSeconds(100), start.plusSeconds(100))).isFalse();
    }

    @Test
    void connectionLostWhenEitherSideIsSilent() {
        Call call = accepted();                                         // both pinged at start + 5
        assertThat(call.endIfSilentSince(start.plusSeconds(5), start.plusSeconds(40))).isFalse();
        call.ping(caller, callerDevice, start.plusSeconds(30));
        assertThat(call.endIfSilentSince(start.plusSeconds(10), start.plusSeconds(40))).as("the callee is silent").isTrue();
        assertThat(call.getStatus()).isEqualTo(CallStatus.ENDED);
        assertThat(call.getEndReason()).isEqualTo(EndReason.CONNECTION_LOST);

        assertThat(ringing().endIfSilentSince(start.plusSeconds(100), start.plusSeconds(100))).isFalse();
    }

    @Test
    void signalsAndPingsOnlyFromTheCallDevicesOfAnAcceptedCall() {
        Call call = ringing();
        assertThat(errorCodeOf(() -> call.requireCallDevice(caller, callerDevice))).isEqualTo("CALL_NOT_ACTIVE");

        call.accept(callee, calleeDevice, start);
        assertThat(call.requireCallDevice(caller, callerDevice)).isEqualTo(calleeDevice);
        assertThat(call.requireCallDevice(callee, calleeDevice)).isEqualTo(callerDevice);
        assertThat(errorCodeOf(() -> call.requireCallDevice(caller, UUID.randomUUID()))).isEqualTo("NOT_CALL_DEVICE");
        assertThat(errorCodeOf(() -> call.ping(callee, callerDevice, start))).isEqualTo("NOT_CALL_DEVICE");

        call.ping(callee, calleeDevice, start.plusSeconds(10));
        assertThat(call.getLastCalleePingAt()).isEqualTo(start.plusSeconds(10));
        assertThat(call.getLastCallerPingAt()).isEqualTo(start);

        call.hangUp(caller, start.plusSeconds(20));
        assertThatThrownBy(() -> call.requireCallDevice(caller, callerDevice)).isInstanceOf(ApiException.class);
    }
}
