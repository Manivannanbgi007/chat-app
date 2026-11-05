package com.chatapp.gateway.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;

/**
 * Base class: starts a real Redis in Docker (needs Docker running) and a fake auth-service.
 * The user-service URL points to a port where nothing listens, so tests can also check the 503 fallback.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
public abstract class AbstractGatewayTest {

    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withCommand("redis-server", "--requirepass", "test")
            .withExposedPorts(6379);
    protected static final FakeBackend AUTH_SERVICE = new FakeBackend();
    protected static final FakeBackend MESSAGE_SERVICE = new FakeBackend();
    protected static final FakeBackend CONVERSATION_SERVICE = new FakeBackend();
    protected static final FakeBackend MEDIA_SERVICE = new FakeBackend();
    protected static final FakeBackend PRESENCE_SERVICE = new FakeBackend();
    protected static final FakeBackend NOTIFICATION_SERVICE = new FakeBackend();
    protected static final FakeBackend CALL_SERVICE = new FakeBackend();
    protected static final FakeBackend STATUS_SERVICE = new FakeBackend();

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("AUTH_SERVICE_URL", AUTH_SERVICE::url);
        registry.add("USER_SERVICE_URL", () -> "http://127.0.0.1:1");
        registry.add("MESSAGE_SERVICE_URL", MESSAGE_SERVICE::url);
        registry.add("CONVERSATION_SERVICE_URL", CONVERSATION_SERVICE::url);
        registry.add("MEDIA_SERVICE_URL", MEDIA_SERVICE::url);
        registry.add("PRESENCE_SERVICE_URL", PRESENCE_SERVICE::url);
        registry.add("NOTIFICATION_SERVICE_URL", NOTIFICATION_SERVICE::url);
        registry.add("CALL_SERVICE_URL", CALL_SERVICE::url);
        registry.add("STATUS_SERVICE_URL", STATUS_SERVICE::url);
        registry.add("MESSAGE_SERVICE_WS_URL", () -> "ws://127.0.0.1:1");
    }

    @Autowired
    protected WebTestClient client;
    @Autowired
    protected ReactiveStringRedisTemplate redis;
}
