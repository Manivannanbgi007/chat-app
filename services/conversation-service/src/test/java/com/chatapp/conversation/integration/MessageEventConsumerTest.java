package com.chatapp.conversation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import com.chatapp.conversation.support.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

/** Sends real MessageCreatedEvents through a real Kafka and checks what the consumer did in the database. */
class MessageEventConsumerTest extends AbstractIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void messageCreatedEventSetsLastMessageAt() throws Exception {
        String conversationId = newConversation();

        send(conversationId, messageCreated(UUID.randomUUID(), conversationId, "2030-05-01T10:00:00.123456Z"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(lastMessageAt(conversationId)).isEqualTo("2030-05-01T10:00:00.123456Z"));
    }

    @Test
    void anOlderMessageNeverMovesTheTimeBackwards() throws Exception {
        String conversationId = newConversation();

        send(conversationId, messageCreated(UUID.randomUUID(), conversationId, "2030-05-02T10:00:00.000000Z"));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(lastMessageAt(conversationId)).isEqualTo("2030-05-02T10:00:00.000000Z"));

        // an event that arrives late (older message) and then a newer one as a marker that both were handled
        send(conversationId, messageCreated(UUID.randomUUID(), conversationId, "2030-05-01T08:00:00.000000Z"));
        send(conversationId, messageCreated(UUID.randomUUID(), conversationId, "2030-05-03T10:00:00.000000Z"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(lastMessageAt(conversationId)).isEqualTo("2030-05-03T10:00:00.000000Z"));
    }

    @Test
    void theSameEventTwiceIsHarmless() throws Exception {
        String conversationId = newConversation();
        UUID eventId = UUID.randomUUID();
        String event = messageCreated(eventId, conversationId, "2030-06-01T10:00:00.000000Z");

        send(conversationId, event);
        send(conversationId, event);
        // a marker event after the duplicate: when it is handled, the duplicate has been handled too
        send(conversationId, messageCreated(UUID.randomUUID(), conversationId, "2030-06-02T10:00:00.000000Z"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(lastMessageAt(conversationId)).isEqualTo("2030-06-02T10:00:00.000000Z"));
        assertThat(countRows("processed_events", "event_id", eventId)).isEqualTo(1);
    }

    @Test
    void anEventForAnUnknownConversationIsIgnoredAndTheConsumerKeepsWorking() throws Exception {
        String conversationId = newConversation();
        UUID unknownConversation = UUID.randomUUID();

        send(unknownConversation.toString(), messageCreated(UUID.randomUUID(), unknownConversation.toString(), "2030-07-01T10:00:00.000000Z"));
        send(conversationId, messageCreated(UUID.randomUUID(), conversationId, "2030-07-02T10:00:00.000000Z"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(lastMessageAt(conversationId)).isEqualTo("2030-07-02T10:00:00.000000Z"));
    }

    @Test
    void otherEventTypesAreIgnored() throws Exception {
        String conversationId = newConversation();
        UUID readEventId = UUID.randomUUID();

        send(conversationId, "{\"eventId\":\"" + readEventId + "\",\"eventType\":\"MessageReadEvent\",\"aggregateId\":\""
                + conversationId + "\",\"occurredAt\":\"2030-08-01T10:00:00Z\",\"schemaVersion\":1,"
                + "\"payload\":{\"conversationId\":\"" + conversationId + "\",\"createdAt\":\"2030-08-01T10:00:00Z\"}}");
        send(conversationId, messageCreated(UUID.randomUUID(), conversationId, "2030-08-02T10:00:00.000000Z"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(lastMessageAt(conversationId)).isEqualTo("2030-08-02T10:00:00.000000Z"));
        assertThat(countRows("processed_events", "event_id", readEventId)).isZero();
    }

    @Test
    void brokenEventsGoStraightToTheDeadLetterTopic() throws Exception {
        String marker = "broken-" + UUID.randomUUID();
        send("bad-key", "{ this is not json " + marker);

        List<ConsumerRecord<String, String>> dead = readDeadLetters(marker);
        assertThat(dead).isNotEmpty();
        assertThat(dead.get(0).key()).isEqualTo("bad-key");
    }

    @Test
    void anEventWithMissingFieldsGoesToTheDeadLetterTopic() throws Exception {
        String marker = UUID.randomUUID().toString();
        // MessageCreatedEvent without conversationId
        send("no-conversation", "{\"eventId\":\"" + marker + "\",\"eventType\":\"MessageCreatedEvent\","
                + "\"payload\":{\"messageId\":\"" + UUID.randomUUID() + "\",\"createdAt\":\"2030-09-01T10:00:00Z\"}}");

        List<ConsumerRecord<String, String>> dead = readDeadLetters(marker);
        assertThat(dead).isNotEmpty();
    }

    // ---- helpers ----

    private String newConversation() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Test");
        UUID bob = USER_SERVICE.addUser("Bob Test");
        return field(startConversation(alice, bob), "$.data.id");
    }

    private void send(String key, String value) throws Exception {
        kafkaTemplate.send("message.events", key, value).get();
    }

    /** lastMessageAt as the text stored in the database (UTC), or null. */
    private String lastMessageAt(String conversationId) {
        List<String> rows = jdbc.queryForList(
                "SELECT DATE_FORMAT(last_message_at, '%Y-%m-%dT%H:%i:%s.%fZ') FROM conversations WHERE id = ?",
                String.class, conversationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String messageCreated(UUID eventId, String conversationId, String createdAt) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"MessageCreatedEvent\",\"aggregateId\":\"" + conversationId
                + "\",\"occurredAt\":\"" + createdAt + "\",\"schemaVersion\":1,\"correlationId\":\"test-corr-1\","
                + "\"payload\":{\"messageId\":\"" + UUID.randomUUID() + "\",\"conversationId\":\"" + conversationId
                + "\",\"senderId\":\"" + UUID.randomUUID() + "\",\"type\":\"TEXT\",\"createdAt\":\"" + createdAt
                + "\",\"recipientIds\":[\"" + UUID.randomUUID() + "\"]}}";
    }

    /** Reads message.events.dlt until a record containing the text shows up (or 30 s pass). */
    private List<ConsumerRecord<String, String>> readDeadLetters(String containsText) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("message.events.dlt"));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> {
                    if (record.value() != null && record.value().contains(containsText)) {
                        found.add(record);
                    }
                });
                return !found.isEmpty();
            });
        }
        return found;
    }
}
