package com.chatapp.call.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base class for tests with the real thing: MySQL, Redis and Kafka in Docker (Docker must be running) plus a fake
 * user-service. Containers are static and shared by all test classes (starting them is the slow part), so every test
 * creates its own people with random ids and never assumes an empty database.
 *
 * What the service publishes is captured for the tests:
 *  - live events on the Redis channel "ws.events"  -> liveEvents()
 *  - Kafka events on the topic "call.events"         -> callEndedEvents()
 * The time-out job runs every 200 ms; tests make a call "old" by moving its times back in the database.
 */
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "JWT_SECRET=" + TestJwt.SECRET,
        "CALL_DB_PASSWORD=unused-testcontainers-provides-the-password",
        "REDIS_PASSWORD=unused-testcontainers-provides-the-password",
        "INTERNAL_SERVICE_TOKEN=" + FakeUserService.INTERNAL_TOKEN,
        "TURN_SECRET=test-turn-secret-0123456789",
        "chatapp.calls.timeout-check-ms=200",
        "chatapp.outbox.poll-ms=200"
})
public abstract class AbstractIntegrationTest {

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.0.0");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withCommand("redis-server", "--requirepass", "test")
            .withExposedPorts(6379);
    protected static final FakeUserService USERS = new FakeUserService();

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
        registry.add("chatapp.services.user-url", USERS::url);
    }

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected JsonMapper jsonMapper;
    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    // ------------------------------------------------------------------ people

    /** One person on one device: a user id and the login session (device) id that goes into the token as "sid". */
    public record Person(UUID userId, UUID deviceId, String name) {
        public String token() {
            return TestJwt.valid(userId, deviceId);
        }

        /** The same person on another device (another login session). */
        public Person otherDevice() {
            return new Person(userId, UUID.randomUUID(), name);
        }
    }

    protected static Person newPerson(String name) {
        String uniqueName = name + " " + UUID.randomUUID().toString().substring(0, 6);
        return new Person(USERS.addUser(uniqueName), UUID.randomUUID(), uniqueName);
    }

    // ------------------------------------------------------------------ HTTP

    protected MvcResult post(String path, Object body, Person who) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request.content(jsonMapper.writeValueAsString(body));
        }
        if (who != null) {
            request.header("Authorization", "Bearer " + who.token());
        }
        return mockMvc.perform(request).andReturn();
    }

    protected MvcResult get(String path, Person who) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.get(path);
        if (who != null) {
            request.header("Authorization", "Bearer " + who.token());
        }
        return mockMvc.perform(request).andReturn();
    }

    protected MvcResult startCall(Person caller, Person callee, String type) throws Exception {
        return post("/api/calls", Map.of("calleeId", callee.userId(), "type", type), caller);
    }

    /** Starts a call that must ring (201 RINGING) and returns its id. */
    protected UUID ringing(Person caller, Person callee) throws Exception {
        MvcResult result = startCall(caller, callee, "VIDEO");
        assertStatus(result, 201);
        assertThat(field(result, "$.data.call.status")).isEqualTo("RINGING");
        return UUID.fromString(field(result, "$.data.call.id"));
    }

    /** A call that rings and was accepted by the callee's device. */
    protected UUID accepted(Person caller, Person callee) throws Exception {
        UUID callId = ringing(caller, callee);
        assertStatus(post("/api/calls/" + callId + "/accept", null, callee), 200);
        return callId;
    }

    protected MvcResult action(UUID callId, String action, Person who) throws Exception {
        return post("/api/calls/" + callId + "/" + action, null, who);
    }

    protected static String field(MvcResult result, String jsonPath) throws Exception {
        Object value = JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
        return value == null ? null : value.toString();
    }

    protected static <T> T read(MvcResult result, String jsonPath) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
    }

    protected static void assertStatus(MvcResult result, int expected) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(expected);
    }

    protected static void assertError(MvcResult result, int status, String errorCode) throws Exception {
        assertStatus(result, status);
        assertThat(field(result, "$.data.errorCode")).isEqualTo(errorCode);
    }

    // ------------------------------------------------------------------ database

    protected String statusInDb(UUID callId) {
        return jdbc.queryForObject("SELECT status FROM calls WHERE id = ?", String.class, callId.toString());
    }

    protected int slotsOf(Person person) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM call_slots WHERE user_id = ?", Integer.class,
                person.userId().toString());
        return count == null ? 0 : count;
    }

    /** Moves a time column of a call back by some seconds, using the database's UTC clock. */
    protected void ageCall(UUID callId, String column, int seconds) {
        jdbc.update("UPDATE calls SET " + column + " = UTC_TIMESTAMP(6) - INTERVAL ? SECOND WHERE id = ?",
                seconds, callId.toString());
    }

    // ------------------------------------------------------------------ waiting

    protected static void eventually(Supplier<Boolean> condition, String what) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            pause(100);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    protected static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ live events (Redis "ws.events")

    /** One published live event: who it is for, the queue, and the parsed event. */
    public record LiveEvent(List<UUID> userIds, String destination, String type, JsonNode data) {
        public boolean isFor(Person person) {
            return userIds.contains(person.userId());
        }

        /** The call id inside the event, whatever its type. */
        public String callId() {
            if (data.has("callId")) {
                return data.get("callId").asText();
            }
            if (data.has("call")) {
                return data.get("call").get("id").asText();
            }
            return data.has("id") ? data.get("id").asText() : null;
        }
    }

    private static final List<String> LIVE_MESSAGES = new CopyOnWriteArrayList<>();
    private static RedisMessageListenerContainer liveListener;

    /** Starts listening once (before the first call of a test run) and keeps listening for all test classes. */
    protected synchronized void listenToLiveEvents() {
        if (liveListener != null) {
            return;
        }
        liveListener = new RedisMessageListenerContainer();
        liveListener.setConnectionFactory(redisConnectionFactory);
        liveListener.addMessageListener((message, pattern) ->
                LIVE_MESSAGES.add(new String(message.getBody(), StandardCharsets.UTF_8)), new ChannelTopic("ws.events"));
        liveListener.afterPropertiesSet();
        liveListener.start();
        pause(500);     // let the subscription settle
    }

    protected List<LiveEvent> liveEvents() {
        List<LiveEvent> events = new ArrayList<>();
        for (String raw : LIVE_MESSAGES) {
            JsonNode envelope = jsonMapper.readTree(raw);
            List<UUID> userIds = new ArrayList<>();
            envelope.get("userIds").forEach(id -> userIds.add(UUID.fromString(id.asText())));
            JsonNode event = jsonMapper.readTree(envelope.get("json").asText());
            events.add(new LiveEvent(userIds, envelope.get("destination").asText(), event.get("type").asText(),
                    event.get("data")));
        }
        return events;
    }

    /** All live events of one call for one person (in the order they were published). */
    protected List<LiveEvent> liveEventsFor(Person person, UUID callId) {
        return liveEvents().stream()
                .filter(event -> event.isFor(person) && callId.toString().equals(event.callId()))
                .toList();
    }

    protected LiveEvent awaitLive(Person person, UUID callId, String type) {
        Predicate<LiveEvent> wanted = event -> event.type().equals(type);
        eventually(() -> liveEventsFor(person, callId).stream().anyMatch(wanted),
                type + " for " + person.name() + " (call " + callId + ")");
        return liveEventsFor(person, callId).stream().filter(wanted).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ Kafka ("call.events")

    private static final List<JsonNode> CALL_EVENTS = new CopyOnWriteArrayList<>();
    private static Thread kafkaReader;

    /** Reads call.events from the beginning in a background thread (once for the whole test run). */
    protected synchronized void readCallEvents() {
        if (kafkaReader != null) {
            return;
        }
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        JsonMapper mapper = jsonMapper;
        kafkaReader = new Thread(() -> {
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
                consumer.subscribe(List.of("call.events"));
                while (!Thread.currentThread().isInterrupted()) {
                    for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                        JsonNode envelope = mapper.readTree(record.value());
                        // remember the Kafka key next to the envelope, to check that key = call id
                        CALL_EVENTS.add(mapper.createObjectNode().put("key", record.key()).set("envelope", envelope));
                    }
                }
            }
        }, "call-events-reader");
        kafkaReader.setDaemon(true);
        kafkaReader.start();
    }

    /** The CallEndedEvents (key + envelope) of one call. */
    protected List<JsonNode> callEndedEvents(UUID callId) {
        return CALL_EVENTS.stream()
                .filter(node -> callId.toString().equals(node.get("key").asText()))
                .toList();
    }

    protected JsonNode awaitCallEnded(UUID callId) {
        eventually(() -> !callEndedEvents(callId).isEmpty(), "CallEndedEvent of call " + callId);
        return callEndedEvents(callId).get(0);
    }
}
