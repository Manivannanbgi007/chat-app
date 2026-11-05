package com.chatapp.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chatapp.notification.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Events that cannot be handled go to "<topic>.dlt" and do not block the events behind them. */
class DeadLetterTest extends AbstractIntegrationTest {

    @AfterEach
    void restorePeers() {
        PEERS.conversationDown = false;
    }

    @Test
    void textThatIsNotJsonGoesToTheDeadLetterTopic() {
        String marker = "garbage-" + UUID.randomUUID();
        publishRaw("message.events", UUID.randomUUID().toString(), "this is not json {{ " + marker);

        assertThat(findInTopic("message.events.dlt", marker, Duration.ofSeconds(30))).isNotNull();
    }

    @Test
    void anEventWithoutItsPayloadGoesToTheDeadLetterTopic() {
        UUID chat = newId();
        UUID eventId = newId();
        // a valid envelope for a MessageCreatedEvent, but "payload" is missing
        publishRaw("message.events", chat.toString(),
                "{\"eventId\":\"" + eventId + "\",\"eventType\":\"MessageCreatedEvent\",\"schemaVersion\":1}");

        assertThat(findInTopic("message.events.dlt", eventId.toString(), Duration.ofSeconds(30))).isNotNull();
        assertThat(eventHandled(eventId)).isFalse();
    }

    @Test
    void aBrokenConversationEventGoesToItsOwnDeadLetterTopic() {
        UUID group = newId();
        UUID eventId = newId();
        publishRaw("conversation.events", group.toString(),
                "{\"eventId\":\"" + eventId + "\",\"eventType\":\"GroupRoleChangedEvent\",\"payload\":{\"role\":\"ADMIN\"}}");

        assertThat(findInTopic("conversation.events.dlt", eventId.toString(), Duration.ofSeconds(30))).isNotNull();
    }

    @Test
    void aBrokenEventDoesNotBlockTheEventsBehindIt() {
        UUID alice = newId();
        UUID bob = newId();
        UUID chat = newId();
        PEERS.addPrivateChat(chat);
        publishRaw("message.events", chat.toString(), "broken {{");
        UUID good = messageCreated(chat, alice, "TEXT", List.of(bob));
        awaitHandled(good);
        assertThat(notificationCount(bob)).isEqualTo(1);
    }

    @Test
    void whenConversationServiceIsDownTheEventIsRetriedAndThenDeadLettered() {
        UUID alice = newId();
        UUID bob = newId();
        UUID chat = newId();
        PEERS.addPrivateChat(chat);
        PEERS.conversationDown = true;

        UUID eventId = messageCreated(chat, alice, "TEXT", List.of(bob));

        assertThat(findInTopic("message.events.dlt", eventId.toString(), Duration.ofSeconds(60))).isNotNull();
        assertThat(notificationCount(bob)).isZero();
        assertThat(eventHandled(eventId)).as("a failed event is not remembered as handled").isFalse();

        PEERS.conversationDown = false;
        UUID next = messageCreated(chat, alice, "TEXT", List.of(bob));
        awaitHandled(next);
        assertThat(notificationCount(bob)).isEqualTo(1);
    }

    @Test
    void anEventThatIsNotForUsIsIgnoredWithoutADeadLetter() {
        UUID chat = newId();
        UUID eventId = newId();
        publish("message.events", chat, event(eventId, "MessageDeliveredEvent", chat, Map.of("messageId", newId().toString())));
        flush("message.events", chat);

        assertThat(findInTopic("message.events.dlt", eventId.toString(), Duration.ofSeconds(3))).isNull();
    }
}
