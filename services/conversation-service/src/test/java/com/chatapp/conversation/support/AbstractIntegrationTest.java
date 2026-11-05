package com.chatapp.conversation.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;
import java.util.UUID;

import com.chatapp.conversation.TestJwt;
import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base class for tests that need the real thing: a MySQL and a Kafka in Docker (Docker must be running)
 * plus a fake user-service. The containers are static and started once, so every test class shares them
 * (much faster) and all test classes share one Spring context. Because the database is shared, each test
 * creates its own users with random ids.
 */
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "JWT_SECRET=" + TestJwt.SECRET,
        "CONVERSATION_DB_PASSWORD=unused-testcontainers-provides-the-password",
        "INTERNAL_SERVICE_TOKEN=" + FakeUserService.TOKEN,
        "chatapp.outbox.poll-ms=200"
})
public abstract class AbstractIntegrationTest {

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.0.0");
    protected static final FakeUserService USER_SERVICE = new FakeUserService();

    static {
        MYSQL.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("chatapp.user-service.url", USER_SERVICE::url);
    }

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected JsonMapper jsonMapper;

    public static String kafkaBootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    protected static String bearer(UUID userId) {
        return "Bearer " + TestJwt.token(userId);
    }

    /** POST /api/conversations as the given user. */
    protected MvcResult startConversation(UUID me, UUID other) throws Exception {
        return mockMvc.perform(post("/api/conversations")
                        .header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("type", "PRIVATE", "userId", other.toString()))))
                .andReturn();
    }

    protected MvcResult getAs(UUID me, String path) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", bearer(me))).andReturn();
    }

    /** POST /api/conversations type GROUP as the given user. */
    protected MvcResult createGroup(UUID me, String name, UUID... members) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("type", "GROUP");
        body.put("name", name);
        body.put("memberIds", java.util.Arrays.stream(members).map(UUID::toString).toList());
        return postAs(me, "/api/conversations", body);
    }

    /** Creates a group and returns its id (expects 201). */
    protected String groupId(UUID me, String name, UUID... members) throws Exception {
        MvcResult result = createGroup(me, name, members);
        assertStatus(result, 201);
        return field(result, "$.data.id");
    }

    protected MvcResult postAs(UUID me, String path, Object body) throws Exception {
        return call(me, post(path), body);
    }

    protected MvcResult putAs(UUID me, String path, Object body) throws Exception {
        return call(me, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path), body);
    }

    protected MvcResult patchAs(UUID me, String path, Object body) throws Exception {
        return call(me, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(path), body);
    }

    protected MvcResult deleteAs(UUID me, String path) throws Exception {
        return call(me, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path), null);
    }

    private MvcResult call(UUID me, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                           Object body) throws Exception {
        request.header("Authorization", bearer(me));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    /** Asserts the status and the errorCode of an error answer. */
    protected static void assertError(MvcResult result, int status, String errorCode) throws Exception {
        assertStatus(result, status);
        org.assertj.core.api.Assertions.assertThat(field(result, "$.data.errorCode")).isEqualTo(errorCode);
    }

    protected static String field(MvcResult result, String jsonPath) throws Exception {
        Object value = JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
        return value == null ? null : value.toString();
    }

    protected static void assertStatus(MvcResult result, int expected) throws Exception {
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(expected);
    }

    protected int countRows(String table, String column, Object value) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, value.toString());
        return count == null ? 0 : count;
    }

    /** Sets a time column to "now + seconds" using the database's UTC clock (the JVM zone is not UTC). */
    protected void setTimeFromNow(String table, String column, UUID id, int seconds) {
        jdbc.update("UPDATE " + table + " SET " + column + " = UTC_TIMESTAMP(6) + INTERVAL ? SECOND WHERE id = ?",
                seconds, id.toString());
    }
}
