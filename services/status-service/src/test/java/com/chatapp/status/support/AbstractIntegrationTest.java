package com.chatapp.status.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base class for tests with the real thing: MySQL in Docker (Docker must be running) plus a fake user-service and a
 * fake media-service. The container is static and shared by all test classes, so every test creates its own people
 * with random ids and never assumes an empty database.
 *
 * The clean-up job is scheduled only once an hour here; tests that need it call StatusCleanupJob.run() themselves,
 * and make a status "expired" by moving its expires_at into the past (database clock, UTC).
 */
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "JWT_SECRET=" + TestJwt.SECRET,
        "STATUS_DB_PASSWORD=unused-testcontainers-provides-the-password",
        "INTERNAL_SERVICE_TOKEN=" + FakeUserService.INTERNAL_TOKEN,
        "chatapp.status.cleanup-interval-ms=3600000"
})
public abstract class AbstractIntegrationTest {

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    protected static final FakeUserService USERS = new FakeUserService();
    protected static final FakeMediaService MEDIA = new FakeMediaService();

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("chatapp.services.user-url", USERS::url);
        registry.add("chatapp.services.media-url", MEDIA::url);
    }

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected JsonMapper jsonMapper;

    @AfterEach
    void servicesAreHealthyAgain() {
        USERS.down = false;
        MEDIA.down = false;
        MEDIA.releaseDown = false;
    }

    // ------------------------------------------------------------------ people

    /** One person (a user id known to the fake user-service) and their access token. */
    public record Person(UUID id, String name) {
        public String token() {
            return TestJwt.valid(id, UUID.randomUUID());
        }
    }

    protected static Person newPerson(String name) {
        String unique = name + " " + UUID.randomUUID().toString().substring(0, 6);
        return new Person(USERS.addUser(unique), unique);
    }

    /** owner adds viewer to owner's contact list (that is what lets the viewer see owner's statuses). */
    protected static void contact(Person owner, Person viewer) {
        USERS.addContact(owner.id(), viewer.id());
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

    protected MvcResult put(String path, Object body, Person who) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.put(path).contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body));
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

    protected MvcResult delete(String path, Person who) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.delete(path).header("Authorization", "Bearer " + who.token()))
                .andReturn();
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    protected MvcResult postText(Person owner, String text, String key) throws Exception {
        return post("/api/statuses", Map.of("type", "TEXT", "text", text, "backgroundColor", "#1D4ED8",
                "idempotencyKey", key), owner);
    }

    protected MvcResult postMedia(Person owner, String type, UUID mediaId, String caption, String key) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("type", type, "mediaId", mediaId, "idempotencyKey", key));
        if (caption != null) {
            body.put("caption", caption);
        }
        return post("/api/statuses", body, owner);
    }

    /** Posts a text status that must succeed (201) and returns its id. */
    protected UUID textStatus(Person owner, String text) throws Exception {
        MvcResult result = postText(owner, text, newKey());
        assertStatus(result, 201);
        return UUID.fromString(field(result, "$.data.id"));
    }

    /** Uploads (in the fake) and posts a photo status that must succeed; returns { statusId, mediaId }. */
    protected UUID[] photoStatus(Person owner, boolean withThumbnail) throws Exception {
        UUID mediaId = MEDIA.addUpload(owner.id(), "IMAGE", withThumbnail);
        MvcResult result = postMedia(owner, "IMAGE", mediaId, "A photo", newKey());
        assertStatus(result, 201);
        return new UUID[] {UUID.fromString(field(result, "$.data.id")), mediaId};
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

    /** Makes a status expired: its expires_at goes one second into the past (database clock, UTC). */
    protected void expire(UUID statusId) {
        jdbc.update("UPDATE statuses SET expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?",
                statusId.toString());
    }

    protected int rows(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }
}
