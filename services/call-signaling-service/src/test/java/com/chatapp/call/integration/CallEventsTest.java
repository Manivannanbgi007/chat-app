package com.chatapp.call.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.chatapp.call.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Every finished call becomes ONE CallEndedEvent on call.events (key = call id), with the documented payload. */
class CallEventsTest extends AbstractIntegrationTest {

    @BeforeEach
    void read() {
        readCallEvents();
    }

    private JsonNode payloadOf(UUID callId) {
        JsonNode record = awaitCallEnded(callId);
        JsonNode envelope = record.get("envelope");
        assertThat(envelope.get("eventType").asText()).isEqualTo("CallEndedEvent");
        assertThat(envelope.get("aggregateId").asText()).isEqualTo(callId.toString());
        assertThat(envelope.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(envelope.get("eventId").asText()).isNotBlank();
        return envelope.get("payload");
    }

    @Test
    void anAnsweredCallIsPublishedWithItsDuration() throws Exception {
        Person alice = newPerson("Alice");
        Person bob = newPerson("Bob");
        UUID callId = accepted(alice, bob);
        ageCall(callId, "answered_at", 42);
        action(callId, "end", bob);

        JsonNode payload = payloadOf(callId);
        assertThat(payload.get("callId").asText()).isEqualTo(callId.toString());
        assertThat(payload.get("callerId").asText()).isEqualTo(alice.userId().toString());
        assertThat(payload.get("calleeId").asText()).isEqualTo(bob.userId().toString());
        assertThat(payload.get("type").asText()).isEqualTo("VIDEO");
        assertThat(payload.get("outcome").asText()).isEqualTo("ANSWERED");
        assertThat(payload.get("endReason").asText()).isEqualTo("HANGUP");
        assertThat(payload.get("durationSeconds").asLong()).isBetween(42L, 47L);
        assertThat(payload.get("createdAt").asText()).isNotBlank();
        assertThat(payload.get("answeredAt").asText()).isNotBlank();
        assertThat(payload.get("endedAt").asText()).isNotBlank();
    }

    @Test
    void aCancelledCallIsMissedWithoutDuration() throws Exception {
        Person alice = newPerson("Alice");
        UUID callId = ringing(alice, newPerson("Bob"));
        action(callId, "end", alice);

        JsonNode payload = payloadOf(callId);
        assertThat(payload.get("outcome").asText()).isEqualTo("MISSED");
        assertThat(payload.get("endReason").asText()).isEqualTo("CANCELLED");
        assertThat(payload.get("durationSeconds").asLong()).isZero();
        assertThat(payload.get("answeredAt").isNull()).isTrue();
    }

    @Test
    void aDeclinedCallIsRejected() throws Exception {
        Person bob = newPerson("Bob");
        UUID callId = ringing(newPerson("Alice"), bob);
        action(callId, "reject", bob);

        JsonNode payload = payloadOf(callId);
        assertThat(payload.get("outcome").asText()).isEqualTo("REJECTED");
        assertThat(payload.get("endReason").asText()).isEqualTo("DECLINED");
    }

    @Test
    void aCallToABusyPersonIsPublishedAsBusy() throws Exception {
        Person bob = newPerson("Bob");
        ringing(newPerson("Alice"), bob);
        Person carol = newPerson("Carol");
        UUID busyCall = UUID.fromString(field(startCall(carol, bob, "AUDIO"), "$.data.call.id"));

        JsonNode payload = payloadOf(busyCall);
        assertThat(payload.get("outcome").asText()).isEqualTo("BUSY");
        assertThat(payload.get("endReason").asText()).isEqualTo("BUSY");
        assertThat(payload.get("type").asText()).isEqualTo("AUDIO");
        assertThat(payload.get("callerId").asText()).isEqualTo(carol.userId().toString());
        assertThat(payload.get("calleeId").asText()).isEqualTo(bob.userId().toString());
    }

    @Test
    void aRingingCallPublishesNothingYet() throws Exception {
        UUID callId = ringing(newPerson("Alice"), newPerson("Bob"));
        pause(1000);
        assertThat(callEndedEvents(callId)).isEmpty();
    }
}
