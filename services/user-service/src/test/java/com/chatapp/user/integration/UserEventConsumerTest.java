package com.chatapp.user.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import com.chatapp.user.support.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

/** Sends real events through a real Kafka and checks what the consumer did in the real database. */
class UserEventConsumerTest extends AbstractIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void userRegisteredEventCreatesUserProfileAndDefaultPrivacy() throws Exception {
        UUID userId = UUID.randomUUID();
        String username = "u" + shortId();

        send(userId.toString(), eventJson(UUID.randomUUID(), "UserRegisteredEvent", userId, username,
                username + "@example.com", "+911234567890", "Alice Test"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(count("users", "id", userId)).isEqualTo(1));

        Map<String, Object> user = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", userId.toString());
        assertThat(user.get("username")).isEqualTo(username);
        assertThat(user.get("email")).isEqualTo(username + "@example.com");
        assertThat(user.get("phone")).isEqualTo("+911234567890");
        assertThat(user.get("account_status")).isEqualTo("ACTIVE");

        Map<String, Object> profile = jdbc.queryForMap("SELECT * FROM user_profiles WHERE user_id = ?", userId.toString());
        assertThat(profile.get("display_name")).isEqualTo("Alice Test");
        assertThat(profile.get("about")).isNull();

        Map<String, Object> privacy = jdbc.queryForMap("SELECT * FROM user_privacy_settings WHERE user_id = ?", userId.toString());
        assertThat(privacy.get("last_seen")).isEqualTo("EVERYONE");
        assertThat(privacy.get("profile_photo")).isEqualTo("EVERYONE");
        assertThat(privacy.get("about")).isEqualTo("EVERYONE");
        assertThat(privacy.get("group_add")).isEqualTo("EVERYONE");
        assertThat(privacy.get("read_receipts")).isIn(true, 1, (byte) 1, 1L);
    }

    @Test
    void sameEventDeliveredTwiceCreatesOnlyOneUser() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String username = "d" + shortId();
        String json = eventJson(eventId, "UserRegisteredEvent", userId, username,
                username + "@example.com", null, "Dup User");

        send(userId.toString(), json);
        send(userId.toString(), json);
        // a marker event sent afterwards: when it is processed, the duplicates before it were handled too
        UUID markerUser = UUID.randomUUID();
        String markerName = "m" + shortId();
        send(markerUser.toString(), eventJson(UUID.randomUUID(), "UserRegisteredEvent", markerUser, markerName,
                markerName + "@example.com", null, "Marker"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(count("users", "id", markerUser)).isEqualTo(1));

        assertThat(count("users", "id", userId)).isEqualTo(1);
        assertThat(count("user_profiles", "user_id", userId)).isEqualTo(1);
        assertThat(count("processed_events", "event_id", eventId)).isEqualTo(1);
    }

    @Test
    void otherEventTypesAreIgnored() throws Exception {
        UUID userId = UUID.randomUUID();
        String username = "o" + shortId();
        send(userId.toString(), eventJson(UUID.randomUUID(), "SomethingElseEvent", userId, username,
                username + "@example.com", null, "Ignored"));
        UUID markerUser = UUID.randomUUID();
        String markerName = "m" + shortId();
        send(markerUser.toString(), eventJson(UUID.randomUUID(), "UserRegisteredEvent", markerUser, markerName,
                markerName + "@example.com", null, "Marker"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(count("users", "id", markerUser)).isEqualTo(1));

        assertThat(count("users", "id", userId)).isZero();
    }

    @Test
    void brokenEventGoesStraightToTheDeadLetterTopic() throws Exception {
        String marker = "not json at all " + UUID.randomUUID();

        send("bad-key", marker);

        List<ConsumerRecord<String, String>> dead = readDeadLetters(marker);
        assertThat(dead).isNotEmpty();
        assertThat(dead.get(0).key()).isEqualTo("bad-key");
    }

    @Test
    void eventWithMissingFieldsGoesToTheDeadLetterTopic() throws Exception {
        UUID userId = UUID.randomUUID();
        // no username / email / displayName
        String json = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"UserRegisteredEvent\","
                + "\"aggregateId\":\"" + userId + "\",\"schemaVersion\":1,\"payload\":{\"userId\":\"" + userId + "\"}}";

        send(userId.toString(), json);

        assertThat(readDeadLetters(userId.toString())).isNotEmpty();
        assertThat(count("users", "id", userId)).isZero();
    }

    // ---- helpers ----

    private void send(String key, String value) throws Exception {
        kafkaTemplate.send("user.events", key, value).get();
    }

    private int count(String table, String column, UUID id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, id.toString());
        return count == null ? 0 : count;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String eventJson(UUID eventId, String type, UUID userId, String username, String email,
                                    String phone, String displayName) {
        String phoneJson = phone == null ? "null" : "\"" + phone + "\"";
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + type + "\",\"aggregateId\":\"" + userId + "\","
                + "\"occurredAt\":\"2026-11-04T10:00:00Z\",\"schemaVersion\":1,\"correlationId\":\"test-corr-1\","
                + "\"payload\":{\"userId\":\"" + userId + "\",\"username\":\"" + username + "\","
                + "\"email\":\"" + email + "\",\"phone\":" + phoneJson + ",\"displayName\":\"" + displayName + "\"}}";
    }

    /** Reads user.events.dlt until a record containing the text shows up (or 30 s pass). */
    private List<ConsumerRecord<String, String>> readDeadLetters(String containsText) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        List<ConsumerRecord<String, String>> found = new java.util.ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("user.events.dlt"));
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
