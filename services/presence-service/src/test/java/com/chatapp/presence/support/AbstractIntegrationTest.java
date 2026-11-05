package com.chatapp.presence.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.GenericContainer;

/**
 * Base class for integration tests: a real Redis (with a password) in Docker and a fake user-service.
 * The containers start once and are shared by all test classes. Needs Docker running.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withCommand("redis-server", "--requirepass", "test")
            .withExposedPorts(6379);
    protected static final FakeUserService USER_SERVICE = new FakeUserService();

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "test");
        registry.add("chatapp.user-service.url", USER_SERVICE::url);
    }

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected StringRedisTemplate redis;

    /** One person on one device: a user id and the login session id of that device. */
    public record Device(UUID userId, UUID sessionId) {

        public static Device newUser() {
            return new Device(UUID.randomUUID(), UUID.randomUUID());
        }

        public Device otherDevice() {
            return new Device(userId, UUID.randomUUID());
        }

        public String token() {
            return TestJwt.token(userId, sessionId);
        }
    }

    protected MvcResult post(String path, Device who) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(path);
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

    protected MvcResult heartbeat(Device who) throws Exception {
        MvcResult result = post("/api/presence/heartbeat", who);
        assertStatus(result, 200);
        return result;
    }

    protected MvcResult goOffline(Device who) throws Exception {
        MvcResult result = post("/api/presence/offline", who);
        assertStatus(result, 200);
        return result;
    }

    protected MvcResult presenceOf(Device viewer, UUID target) throws Exception {
        return get("/api/presence/" + target, viewer);
    }

    protected MvcResult internalOnline(String userIds) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get("/internal/presence/online")
                .param("userIds", userIds)
                .header("X-Internal-Token", TestJwt.INTERNAL_TOKEN)).andReturn();
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
