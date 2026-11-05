package com.chatapp.media.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base class for tests that need the real thing: MySQL and a real S3-compatible storage (RustFS) in Docker.
 * Docker must be running. The containers are static and started once, so every test class shares them and
 * all test classes share one Spring context. Because they share one database, every test uses its own random user.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    protected static final String BUCKET = "chat-media";

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    static final GenericContainer<?> STORAGE = new GenericContainer<>(DockerImageName.parse("rustfs/rustfs:1.0.4"))
            .withEnv("RUSTFS_ACCESS_KEY", "test-access-key")
            .withEnv("RUSTFS_SECRET_KEY", "test-secret-key-123456")
            .withEnv("RUSTFS_ADDRESS", ":9000")
            .withEnv("RUSTFS_CONSOLE_ENABLE", "false")
            .withExposedPorts(9000)
            .waitingFor(Wait.forListeningPort());

    /** A client to look inside the storage, independent of the service under test. */
    protected static final S3Client TEST_S3;

    static {
        Startables.deepStart(MYSQL, STORAGE).join();
        TEST_S3 = S3Client.builder()
                .endpointOverride(URI.create(storageUrl()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access-key", "test-secret-key-123456")))
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
        waitUntilStorageAnswers();
    }

    private static String storageUrl() {
        return "http://" + STORAGE.getHost() + ":" + STORAGE.getMappedPort(9000);
    }

    /** The port is open before the storage is ready to answer; wait for a real answer. */
    private static void waitUntilStorageAnswers() {
        long deadline = System.currentTimeMillis() + 60_000;
        while (true) {
            try {
                TEST_S3.listBuckets();
                return;
            } catch (RuntimeException e) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("The test storage did not become ready", e);
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        }
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        // The service and the test run on the same machine, so the "internal" and the "public" address are the same here.
        registry.add("chatapp.s3.endpoint", AbstractIntegrationTest::storageUrl);
        registry.add("chatapp.s3.public-endpoint", AbstractIntegrationTest::storageUrl);
    }

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected JsonMapper jsonMapper;

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    // ------------------------------------------------------------------ calls

    protected static String bearer(UUID userId) {
        return "Bearer " + TestJwt.token(userId);
    }

    /** POST /api/media/upload as the given user. */
    protected MvcResult upload(UUID user, String fileName, String claimedType, byte[] content, String purpose) throws Exception {
        return upload(user, fileName, claimedType, content, purpose, null);
    }

    protected MvcResult upload(UUID user, String fileName, String claimedType, byte[] content, String purpose,
                               Integer durationSeconds) throws Exception {
        var request = multipart("/api/media/upload")
                .file(new MockMultipartFile("file", fileName, claimedType, content))
                .header("Authorization", bearer(user));
        if (purpose != null) {
            request.param("purpose", purpose);
        }
        if (durationSeconds != null) {
            request.param("durationSeconds", durationSeconds.toString());
        }
        return mockMvc.perform(request).andReturn();
    }

    /** Uploads (expects 201) and returns the new media id. */
    protected UUID uploadOk(UUID user, String fileName, String claimedType, byte[] content, String purpose) throws Exception {
        MvcResult result = upload(user, fileName, claimedType, content, purpose);
        assertStatus(result, 201);
        return UUID.fromString(field(result, "$.data.id"));
    }

    protected MvcResult getAs(UUID user, String path) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header("Authorization", bearer(user))).andReturn();
    }

    protected MvcResult deleteAs(UUID user, String path) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path)
                .header("Authorization", bearer(user))).andReturn();
    }

    /** A call from another service (message-service) with the internal token. */
    protected MvcResult internalPost(String path, Object body) throws Exception {
        return mockMvc.perform(post(path)
                .header("X-Internal-Token", TestJwt.INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body))).andReturn();
    }

    protected MvcResult attach(UUID mediaId, UUID ownerId, String kind, String reference) throws Exception {
        return internalPost("/internal/media/" + mediaId + "/attach",
                Map.of("ownerId", ownerId, "kind", kind, "reference", reference));
    }

    protected MvcResult downloadLink(UUID mediaId, UUID requesterId, String variant) throws Exception {
        return internalPost("/internal/media/" + mediaId + "/download-link",
                Map.of("requesterId", requesterId, "variant", variant));
    }

    /** Downloads from a signed link exactly like a browser would (plain GET, no extra headers). */
    protected HttpResponse<byte[]> fetch(String url) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    // --------------------------------------------------------------- database

    protected String objectKeyOf(UUID mediaId) {
        return jdbc.queryForObject("SELECT object_key FROM media_files WHERE id = ?", String.class, mediaId.toString());
    }

    protected String thumbnailKeyOf(UUID mediaId) {
        return jdbc.queryForObject("SELECT thumbnail_object_key FROM media_metadata WHERE media_id = ?", String.class, mediaId.toString());
    }

    protected String statusOf(UUID mediaId) {
        return jdbc.queryForObject("SELECT status FROM media_files WHERE id = ?", String.class, mediaId.toString());
    }

    protected int countLogs(UUID mediaId, String action) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM media_access_logs WHERE media_id = ? AND action = ?",
                Integer.class, mediaId.toString(), action);
        return count == null ? 0 : count;
    }

    /** Makes a file look older (the JVM zone is not UTC, so the database clock is used). */
    protected void ageHours(UUID mediaId, int hours) {
        jdbc.update("UPDATE media_files SET created_at = UTC_TIMESTAMP(6) - INTERVAL ? HOUR WHERE id = ?", hours, mediaId.toString());
    }

    // ---------------------------------------------------------------- storage

    protected boolean objectExists(String key) {
        try {
            TEST_S3.headObject(b -> b.bucket(BUCKET).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    // ----------------------------------------------------------------- helpers

    protected static String field(MvcResult result, String jsonPath) throws Exception {
        Object value = JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
        return value == null ? null : value.toString();
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
}
