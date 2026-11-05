package com.chatapp.auth.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
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
 * Base class for integration tests. Starts MySQL, Redis and Kafka once in Docker and shares them
 * between all test classes (starting containers is the slow part). Needs Docker running.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestMailConfig.class)
public abstract class AbstractIntegrationTest {

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withCommand("redis-server", "--requirepass", "test")
            .withExposedPorts(6379);
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

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
    }

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected JsonMapper jsonMapper;
    @Autowired
    protected FakeEmailService emails;
    @Autowired
    protected JdbcTemplate jdbc;

    protected static final String PASSWORD = "Passw0rd123";

    /** A registered user as the tests see it. */
    public record TestUser(String userId, String username, String email, String password) {
    }

    /** POST a JSON body and return the MockMvc result (status is checked by the caller). */
    protected MvcResult postJson(String path, Object body, String bearerToken) throws Exception {
        MockHttpServletRequestBuilder request = post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body));
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        return mockMvc.perform(request).andReturn();
    }

    protected MvcResult postJson(String path, Object body) throws Exception {
        return postJson(path, body, null);
    }

    protected String field(MvcResult result, String jsonPath) throws Exception {
        Object value = JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
        return value == null ? null : value.toString();
    }

    /** Registers a new user (unique names so tests never clash) but does not verify the email. */
    protected TestUser register() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "user_" + suffix;
        String email = username + "@example.com";
        MvcResult result = postJson("/api/auth/register", Map.of(
                "username", username,
                "email", email,
                "password", PASSWORD,
                "displayName", "Test " + suffix));
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return new TestUser(field(result, "$.data.userId"), username, email, PASSWORD);
    }

    /** Registers and verifies the email with the code the fake mail service received. */
    protected TestUser registerVerified() throws Exception {
        TestUser user = register();
        MvcResult result = postJson("/api/auth/verify-otp", Map.of(
                "identifier", user.email(),
                "otp", emails.lastCodeFor(user.email()),
                "purpose", "EMAIL_VERIFY"));
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return user;
    }

    /** Logs in and returns the full response JSON (accessToken, refreshToken, ...). */
    protected MvcResult login(TestUser user, String deviceName) throws Exception {
        return postJson("/api/auth/login", Map.of(
                "identifier", user.email(),
                "password", user.password(),
                "deviceName", deviceName));
    }

    /**
     * Sets a time column to "now + seconds" (negative = in the past), using the database's UTC clock.
     * (Never pass java.sql.Timestamp from the test: the JVM zone is not UTC but the app stores UTC.)
     */
    protected void setTimeFromNow(String table, String column, String whereColumn, String whereValue, int seconds) {
        jdbc.update("UPDATE " + table + " SET " + column + " = UTC_TIMESTAMP(6) + INTERVAL ? SECOND WHERE "
                + whereColumn + " = ?", seconds, whereValue);
    }

    protected void assertStatus(MvcResult result, int expected) throws Exception {
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(expected);
    }
}
