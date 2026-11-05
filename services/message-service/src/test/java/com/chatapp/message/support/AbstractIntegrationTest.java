package com.chatapp.message.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base class for integration tests. Starts MySQL, Redis and Kafka once in Docker (shared by all test classes) and two
 * fake peer services (conversation-service, user-service). Needs Docker running.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withCommand("redis-server", "--requirepass", "test")
            .withExposedPorts(6379);
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");
    protected static final FakePeers PEERS = new FakePeers();

    static {
        Startables.deepStart(MYSQL, REDIS, KAFKA).join();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "test");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("chatapp.services.conversation-url", PEERS::conversationUrl);
        registry.add("chatapp.services.user-url", PEERS::userUrl);
        registry.add("chatapp.services.media-url", PEERS::mediaUrl);
    }

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected JsonMapper jsonMapper;
    @Autowired
    protected JdbcTemplate jdbc;

    /** One person on one device: a user id and the login session id of that device. */
    public record Device(UUID userId, UUID sessionId) {
        public static Device newUser() {
            return new Device(UUID.randomUUID(), UUID.randomUUID());
        }

        public Device otherDevice() {
            return new Device(userId, UUID.randomUUID());
        }

        public String token() {
            return TestJwt.valid(userId, sessionId);
        }
    }

    /** A private conversation between two users (registered in the fake conversation-service). */
    public record Chat(UUID id, Device a, Device b) {
    }

    protected Chat newChat() {
        Device a = Device.newUser();
        Device b = Device.newUser();
        UUID id = UUID.randomUUID();
        PEERS.addConversation(id, a.userId(), b.userId());
        return new Chat(id, a, b);
    }

    /** A group (registered in the fake conversation-service): an admin plus other members. */
    public record Group(UUID id, Device admin, java.util.List<Device> others) {
        public Device member(int index) {
            return others.get(index);
        }
    }

    protected Group newGroup(int otherMembers) {
        Device admin = Device.newUser();
        java.util.List<Device> others = new java.util.ArrayList<>();
        for (int i = 0; i < otherMembers; i++) {
            others.add(Device.newUser());
        }
        UUID id = UUID.randomUUID();
        PEERS.addGroup(id, admin.userId(), others.stream().map(Device::userId).toArray(UUID[]::new));
        return new Group(id, admin, others);
    }

    // ------------------------------------------------------- conversation.events

    private static org.apache.kafka.clients.producer.KafkaProducer<String, String> eventProducer;

    private static synchronized org.apache.kafka.clients.producer.KafkaProducer<String, String> eventProducer() {
        if (eventProducer == null) {
            java.util.Properties props = new java.util.Properties();
            props.put(org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
            props.put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                    org.apache.kafka.common.serialization.StringSerializer.class.getName());
            props.put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                    org.apache.kafka.common.serialization.StringSerializer.class.getName());
            eventProducer = new org.apache.kafka.clients.producer.KafkaProducer<>(props);
        }
        return eventProducer;
    }

    /** Puts a raw record on conversation.events (key = conversation id), like conversation-service's outbox would. */
    protected void publishRaw(String key, String value) throws Exception {
        eventProducer().send(new org.apache.kafka.clients.producer.ProducerRecord<>("conversation.events", key, value))
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Publishes one conversation-service event in the standard envelope. Returns its event id. */
    protected UUID publishEvent(String eventType, UUID conversationId, Map<String, Object> payload) throws Exception {
        UUID eventId = UUID.randomUUID();
        publishEvent(eventId, eventType, conversationId, payload);
        return eventId;
    }

    protected void publishEvent(UUID eventId, String eventType, UUID conversationId, Map<String, Object> payload)
            throws Exception {
        Map<String, Object> withId = new java.util.LinkedHashMap<>();
        withId.put("conversationId", conversationId.toString());
        withId.putAll(payload);
        Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("aggregateId", conversationId.toString());
        envelope.put("occurredAt", java.time.Instant.now().toString());
        envelope.put("schemaVersion", 1);
        envelope.put("correlationId", "test-" + eventId.toString().substring(0, 8));
        envelope.put("payload", withId);
        publishRaw(conversationId.toString(), jsonMapper.writeValueAsString(envelope));
    }

    /** Waits (up to 30 s) until the condition is true: Kafka consumers work in the background. */
    protected static void await(String what, java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.call())) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    // ------------------------------------------------------------ HTTP helpers

    protected MvcResult post(String path, Object body, Device who) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body));
        if (who != null) {
            request.header("Authorization", "Bearer " + who.token());
        }
        return mockMvc.perform(request).andReturn();
    }

    protected MvcResult get(String path, Device who) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.get(path);
        if (who != null) {
            request.header("Authorization", "Bearer " + who.token());
        }
        return mockMvc.perform(request).andReturn();
    }

    protected MvcResult send(Device from, UUID conversationId, String content, String key) throws Exception {
        return post("/api/messages", Map.of("conversationId", conversationId, "type", "TEXT",
                "content", content, "idempotencyKey", key), from);
    }

    /** Sends and returns the new message id (expects 201). */
    protected UUID sendOk(Device from, UUID conversationId, String content) throws Exception {
        MvcResult result = send(from, conversationId, content, newKey());
        assertStatus(result, 201);
        return UUID.fromString(field(result, "$.data.id"));
    }

    protected MvcResult markDelivered(Device who, UUID... messageIds) throws Exception {
        return post("/api/messages/delivered", Map.of("messageIds", java.util.List.of(messageIds)), who);
    }

    protected MvcResult markRead(Device who, UUID conversationId, UUID upTo) throws Exception {
        return post("/api/conversations/" + conversationId + "/read", Map.of("upToMessageId", upTo), who);
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
}
