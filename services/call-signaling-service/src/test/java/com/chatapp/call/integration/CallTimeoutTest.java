package com.chatapp.call.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.chatapp.call.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The time-out job (every 200 ms in tests). Instead of waiting 30 real seconds, a test moves the call's times back in
 * the database, exactly as if the time had passed.
 */
class CallTimeoutTest extends AbstractIntegrationTest {

    @BeforeEach
    void listen() {
        listenToLiveEvents();
        readCallEvents();
    }

    @Test
    void aCallNobodyAnswersBecomesMissedAndFreesBothPeople() throws Exception {
        Person alice = newPerson("Alice");
        Person bob = newPerson("Bob");
        UUID callId = ringing(alice, bob);

        ageCall(callId, "created_at", 31);

        eventually(() -> "MISSED".equals(statusInDb(callId)), "the ring time-out");
        JsonNode toBob = awaitLive(bob, callId, "CALL_ENDED").data();
        assertThat(toBob.get("endReason").asText()).isEqualTo("NO_ANSWER");
        assertThat(toBob.get("direction").asText()).isEqualTo("INCOMING");
        assertThat(awaitLive(alice, callId, "CALL_ENDED").data().get("status").asText()).isEqualTo("MISSED");
        assertThat(slotsOf(alice) + slotsOf(bob)).isZero();
        assertThat(awaitCallEnded(callId).get("envelope").get("payload").get("outcome").asText()).isEqualTo("MISSED");
    }

    @Test
    void aCallStillWithinTheRingTimeKeepsRinging() throws Exception {
        UUID callId = ringing(newPerson("Alice"), newPerson("Bob"));
        ageCall(callId, "created_at", 20);
        pause(1000);
        assertThat(statusInDb(callId)).isEqualTo("RINGING");
    }

    @Test
    void anAcceptedCallEndsWhenOneSideStopsPinging() throws Exception {
        Person alice = newPerson("Alice");
        Person bob = newPerson("Bob");
        UUID callId = accepted(alice, bob);

        ageCall(callId, "last_callee_ping_at", 31);

        eventually(() -> "ENDED".equals(statusInDb(callId)), "the ping time-out");
        assertThat(awaitLive(alice, callId, "CALL_ENDED").data().get("endReason").asText()).isEqualTo("CONNECTION_LOST");
        assertThat(slotsOf(alice) + slotsOf(bob)).isZero();
        JsonNode payload = awaitCallEnded(callId).get("envelope").get("payload");
        assertThat(payload.get("outcome").asText()).isEqualTo("ANSWERED");
        assertThat(payload.get("endReason").asText()).isEqualTo("CONNECTION_LOST");
    }

    @Test
    void anAcceptedCallWithRecentPingsKeepsRunning() throws Exception {
        UUID callId = accepted(newPerson("Alice"), newPerson("Bob"));
        ageCall(callId, "last_caller_ping_at", 20);
        ageCall(callId, "last_callee_ping_at", 20);
        pause(1000);
        assertThat(statusInDb(callId)).isEqualTo("ACCEPTED");
    }

    @Test
    void aPingKeepsThatSideAlive() throws Exception {
        Person alice = newPerson("Alice");
        Person bob = newPerson("Bob");
        UUID callId = accepted(alice, bob);
        ageCall(callId, "last_caller_ping_at", 25);

        assertStatus(action(callId, "ping", alice), 200);

        String lastPing = jdbc.queryForObject(
                "SELECT TIMESTAMPDIFF(SECOND, last_caller_ping_at, UTC_TIMESTAMP(6)) FROM calls WHERE id = ?",
                String.class, callId.toString());
        assertThat(Integer.parseInt(lastPing)).isLessThan(5);
    }
}
