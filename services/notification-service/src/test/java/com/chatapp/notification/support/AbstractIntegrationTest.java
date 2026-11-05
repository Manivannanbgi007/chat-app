package com.chatapp.notification.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;

import com.chatapp.notification.TestJwt;
import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base class for tests with the real thing: MySQL, Kafka and Redis in Docker (Docker must be running) plus three
 * fake peer services. Containers are static and shared by all test classes (starting them is the slow part), so
 * every test creates its own users and chats with random ids and never assumes an empty database.
 *
 * Background jobs: the push job is switched off (poll every hour) and called by the tests themselves,
 * so a test decides exactly when a push is sent. Kafka retries are shortened to a few milliseconds.
 */
@AutoConfigureMockMvc
@Import(TestPushConfig.class)
@SpringBootTest(properties = {
        "JWT_SECRET=" + TestJwt.SECRET,
        "NOTIFICATION_DB_PASSWORD=unused-testcontainers-provides-the-password",
        "REDIS_PASSWORD=unused-testcontainers-provides-the-password",
        "INTERNAL_SERVICE_TOKEN=" + FakePeers.INTERNAL_TOKEN,
        "chatapp.push.poll-ms=3600000",
        "chatapp.kafka.retry-initial-ms=50",
        "chatapp.kafka.retry-multiplier=2.0"
})
public abstract class AbstractIntegrationTest {

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.0.0");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withCommand("redis-server", "--requirepass", "test")
            .withExposedPorts(6379);
    protected static final FakePeers PEERS = new FakePeers();

    static {
        Startables.deepStart(MYSQL, KAFKA, REDIS).join();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "test");
        registry.add("chatapp.services.conversation-url", PEERS::conversationUrl);
        registry.add("chatapp.services.user-url", PEERS::userUrl);
        registry.add("chatapp.services.presence-url", PEERS::presenceUrl);
    }

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected JsonMapper jsonMapper;
    @Autowired
    protected TestPushSender pushSender;
    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    // ------------------------------------------------------------------ ids and events

    protected static UUID newId() {
        return UUID.randomUUID();
    }

    private static KafkaProducer<String, String> producer;

    private static synchronized KafkaProducer<String, String> producer() {
        if (producer == null) {
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            producer = new KafkaProducer<>(props);
        }
        return producer;
    }

    /** The standard envelope around a payload (see KAFKA_EVENT_DESIGN.md). Returns the JSON text. */
    protected String event(UUID eventId, String eventType, UUID aggregateId, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("aggregateId", aggregateId.toString());
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("schemaVersion", 1);
        envelope.put("correlationId", "test-" + UUID.randomUUID());
        envelope.put("payload", payload);
        return jsonMapper.writeValueAsString(envelope);
    }

    protected void publish(String topic, UUID key, String json) {
        publishRaw(topic, key.toString(), json);
    }

    protected void publishRaw(String topic, String key, String json) {
        try {
            producer().send(new ProducerRecord<>(topic, key, json)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A MessageCreatedEvent as message-service publishes it (there is no text in it). */
    protected UUID messageCreated(UUID conversationId, UUID senderId, String type, List<UUID> recipients) {
        UUID eventId = newId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", newId().toString());
        payload.put("conversationId", conversationId.toString());
        payload.put("senderId", senderId.toString());
        payload.put("type", type);
        payload.put("createdAt", Instant.now().toString());
        payload.put("recipientIds", recipients.stream().map(UUID::toString).toList());
        publish("message.events", conversationId, event(eventId, "MessageCreatedEvent", conversationId, payload));
        return eventId;
    }

    protected void messageRead(UUID conversationId, UUID senderId, UUID recipientId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", newId().toString());
        payload.put("conversationId", conversationId.toString());
        payload.put("senderId", senderId.toString());
        payload.put("recipientId", recipientId.toString());
        payload.put("deviceId", newId().toString());
        payload.put("at", Instant.now().toString());
        publish("message.events", conversationId, event(newId(), "MessageReadEvent", conversationId, payload));
    }

    // ------------------------------------------------------------------- waiting

    /** Polls until the condition is true (events travel through Kafka, so results appear a moment later). */
    protected static void eventually(Supplier<Boolean> condition, String what) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    protected boolean eventHandled(UUID eventId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId.toString());
        return count != null && count > 0;
    }

    protected void awaitHandled(UUID eventId) {
        eventually(() -> eventHandled(eventId), "event " + eventId + " to be handled");
    }

    /**
     * Waits until every event published BEFORE this call to the same topic with the same Kafka key (so the same
     * partition, handled in order) was processed: it sends one more event with that key and waits until it is
     * recorded. Use it before asserting that something did NOT happen.
     */
    protected void flush(String topic, UUID key) {
        UUID markerEvent = newId();
        if ("message.events".equals(topic)) {
            UUID markerChat = newId();
            PEERS.addPrivateChat(markerChat);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageId", newId().toString());
            payload.put("conversationId", markerChat.toString());
            payload.put("senderId", newId().toString());
            payload.put("type", "TEXT");
            payload.put("createdAt", Instant.now().toString());
            payload.put("recipientIds", List.of(newId().toString()));
            publishRaw(topic, key.toString(), event(markerEvent, "MessageCreatedEvent", markerChat, payload));
        } else if ("call.events".equals(topic)) {
            // a missed call between two throw-away users: it is always recorded as handled
            publishRaw(topic, key.toString(), event(markerEvent, "CallEndedEvent", key,
                    callEndedPayload(key, newId(), newId(), "AUDIO", "MISSED")));
        } else {
            UUID markerChat = newId();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("conversationId", markerChat.toString());
            payload.put("userId", newId().toString());
            payload.put("role", "ADMIN");
            publishRaw(topic, key.toString(), event(markerEvent, "GroupRoleChangedEvent", markerChat, payload));
        }
        awaitHandled(markerEvent);
    }

    // ---------------------------------------------------------- conversation events

    protected UUID groupCreated(UUID conversationId, UUID createdBy, String createdByName, String groupName, List<UUID> participants) {
        UUID eventId = newId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId.toString());
        payload.put("type", "GROUP");
        payload.put("createdBy", createdBy.toString());
        payload.put("createdByName", createdByName);
        payload.put("groupName", groupName);
        payload.put("participantIds", participants.stream().map(UUID::toString).toList());
        publish("conversation.events", conversationId, event(eventId, "ConversationCreatedEvent", conversationId, payload));
        return eventId;
    }

    protected UUID membersAdded(UUID conversationId, UUID actorId, String actorName, List<UUID> added) {
        UUID eventId = newId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId.toString());
        payload.put("actorId", actorId.toString());
        payload.put("actorName", actorName);
        payload.put("members", added.stream().map(id -> Map.of("userId", id.toString(), "role", "MEMBER")).toList());
        publish("conversation.events", conversationId, event(eventId, "GroupMembersAddedEvent", conversationId, payload));
        return eventId;
    }

    protected UUID memberRemoved(UUID conversationId, UUID userId, String reason, String actorName) {
        UUID eventId = newId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId.toString());
        payload.put("userId", userId.toString());
        payload.put("reason", reason);
        payload.put("actorName", actorName);
        publish("conversation.events", conversationId, event(eventId, "GroupMemberRemovedEvent", conversationId, payload));
        return eventId;
    }

    protected UUID roleChanged(UUID conversationId, UUID userId, String role) {
        UUID eventId = newId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId.toString());
        payload.put("userId", userId.toString());
        payload.put("role", role);
        publish("conversation.events", conversationId, event(eventId, "GroupRoleChangedEvent", conversationId, payload));
        return eventId;
    }

    // ------------------------------------------------------------------ call events

    /** The payload of a CallEndedEvent as call-signaling-service publishes it (KAFKA_EVENT_DESIGN.md, Phase 7). */
    protected static Map<String, Object> callEndedPayload(UUID callId, UUID callerId, UUID calleeId, String type,
                                                          String outcome) {
        boolean answered = "ANSWERED".equals(outcome);
        Instant now = Instant.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("callId", callId.toString());
        payload.put("callerId", callerId.toString());
        payload.put("calleeId", calleeId.toString());
        payload.put("type", type);
        payload.put("outcome", outcome);
        payload.put("endReason", switch (outcome) {
            case "ANSWERED" -> "HANGUP";
            case "REJECTED" -> "DECLINED";
            case "BUSY" -> "BUSY";
            default -> "NO_ANSWER";
        });
        payload.put("createdAt", now.minusSeconds(40).toString());
        payload.put("answeredAt", answered ? now.minusSeconds(30).toString() : null);
        payload.put("endedAt", now.toString());
        payload.put("durationSeconds", answered ? 30 : 0);
        return payload;
    }

    /** Publishes a CallEndedEvent (key = call id) and returns its event id. */
    protected UUID callEnded(UUID callerId, UUID calleeId, String type, String outcome) {
        UUID eventId = newId();
        UUID callId = newId();
        publish("call.events", callId, event(eventId, "CallEndedEvent", callId,
                callEndedPayload(callId, callerId, calleeId, type, outcome)));
        return eventId;
    }

    // ------------------------------------------------------------- database views

    protected long notificationCount(UUID userId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ?", Long.class, userId.toString());
        return count == null ? 0 : count;
    }

    protected List<Map<String, Object>> notificationsOf(UUID userId) {
        return jdbc.queryForList("SELECT * FROM notifications WHERE user_id = ? ORDER BY created_at, id", userId.toString());
    }

    /** Inserts a notification directly (for API tests). updatedAgoSeconds orders the list. */
    protected UUID insertNotification(UUID userId, String type, String title, String body, boolean read, int updatedAgoSeconds) {
        UUID id = newId();
        jdbc.update("INSERT INTO notifications (id, user_id, type, title, body, conversation_id, item_count, is_read, unread_key, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, NULL, 1, ?, ?, UTC_TIMESTAMP(6) - INTERVAL ? SECOND, "
                        + "UTC_TIMESTAMP(6) - INTERVAL ? SECOND)",
                id.toString(), userId.toString(), type, title, body, read, read ? null : userId + ":" + type + ":" + id,
                updatedAgoSeconds + 5, updatedAgoSeconds);
        return id;
    }

    // ---------------------------------------------------------------- Redis (live)

    /** Collects what is published to "ws.events" while the test runs. */
    protected class LiveListener implements AutoCloseable {
        public final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final RedisMessageListenerContainer container = new RedisMessageListenerContainer();

        public LiveListener() {
            container.setConnectionFactory(redisConnectionFactory);
            container.addMessageListener((message, pattern) ->
                    messages.add(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8)), new ChannelTopic("ws.events"));
            container.afterPropertiesSet();
            container.start();
            try {
                Thread.sleep(500);      // let the subscription settle
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** The envelopes received so far that are addressed to this user. */
        public List<String> forUser(UUID userId) {
            return messages.stream().filter(m -> m.contains(userId.toString())).toList();
        }

        @Override
        public void close() {
            container.stop();
            try {
                container.destroy();
            } catch (Exception ignored) {
                // nothing to clean up
            }
        }
    }

    // --------------------------------------------------------------- Kafka (dead letters)

    /** Reads a dead-letter topic and returns the first record whose value contains the text, or null. */
    protected ConsumerRecord<String, String> findInTopic(String topic, String text, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value() != null && record.value().contains(text)) {
                        return record;
                    }
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------- HTTP

    protected static String bearer(UUID userId) {
        return "Bearer " + TestJwt.token(userId);
    }

    protected MvcResult getAs(UUID user, String path) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", bearer(user))).andReturn();
    }

    protected MvcResult putAs(UUID user, String path, Object body) throws Exception {
        return send(put(path), user, body);
    }

    protected MvcResult postAs(UUID user, String path, Object body) throws Exception {
        return send(post(path), user, body);
    }

    protected MvcResult deleteAs(UUID user, String path) throws Exception {
        return mockMvc.perform(delete(path).header("Authorization", bearer(user))).andReturn();
    }

    private MvcResult send(MockHttpServletRequestBuilder request, UUID user, Object body) throws Exception {
        request.header("Authorization", bearer(user));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    protected String field(MvcResult result, String jsonPath) throws Exception {
        Object value = JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
        return value == null ? null : value.toString();
    }

    protected <T> T read(MvcResult result, String jsonPath) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
    }

    protected void assertStatus(MvcResult result, int expected) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(expected);
    }

    // ------------------------------------------------------------ test data helpers

    protected void setPreferences(UUID user, boolean push, boolean messages, boolean groups) throws Exception {
        setPreferences(user, push, messages, groups, true);
    }

    protected void setPreferences(UUID user, boolean push, boolean messages, boolean groups, boolean calls) throws Exception {
        MvcResult result = putAs(user, "/api/notifications/preferences",
                Map.of("pushEnabled", push, "messagesEnabled", messages, "groupsEnabled", groups, "callsEnabled", calls));
        assertStatus(result, 200);
    }

    protected void registerDevice(UUID user, String token) throws Exception {
        assertStatus(postAs(user, "/api/notifications/device-tokens", Map.of("token", token, "platform", "WEB")), 200);
    }

    /** A token that is long enough to be accepted and unique for this test run. */
    protected static String newToken() {
        return "token-" + UUID.randomUUID();
    }

    protected List<Map<String, Object>> deliveriesOf(UUID userId) {
        return jdbc.queryForList("SELECT * FROM notification_delivery_logs WHERE user_id = ? ORDER BY created_at, id", userId.toString());
    }
}
