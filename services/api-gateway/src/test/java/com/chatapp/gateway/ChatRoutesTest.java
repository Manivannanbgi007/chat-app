package com.chatapp.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.chatapp.gateway.support.AbstractGatewayTest;
import com.chatapp.gateway.support.FakeBackend;
import com.chatapp.gateway.support.TestJwt;
import org.junit.jupiter.api.Test;

/** Which service answers which path. The message paths under /api/conversations/... must not go to conversation-service. */
class ChatRoutesTest extends AbstractGatewayTest {

    private String bearer() {
        return "Bearer " + TestJwt.valid(UUID.randomUUID().toString());
    }

    private void expectRoutedTo(String method, String path, FakeBackend expected, FakeBackend... others) {
        expected.lastPath.set(null);
        for (FakeBackend other : others) {
            other.lastPath.set(null);
        }
        client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path)
                .header("Authorization", bearer())
                .exchange()
                .expectStatus().isOk();
        assertThat(expected.lastPath.get()).as("path " + path).isEqualTo(path.split("\\?")[0]);
        for (FakeBackend other : others) {
            assertThat(other.lastPath.get()).as("path " + path + " must not reach the wrong service").isNull();
        }
    }

    @Test
    void messageEndpointsGoToMessageService() {
        UUID id = UUID.randomUUID();
        expectRoutedTo("POST", "/api/messages", MESSAGE_SERVICE, CONVERSATION_SERVICE);
        expectRoutedTo("GET", "/api/messages/summaries", MESSAGE_SERVICE, CONVERSATION_SERVICE);
        expectRoutedTo("GET", "/api/messages/sync?after=abc", MESSAGE_SERVICE, CONVERSATION_SERVICE);
        expectRoutedTo("POST", "/api/messages/delivered", MESSAGE_SERVICE, CONVERSATION_SERVICE);
        expectRoutedTo("GET", "/api/conversations/" + id + "/messages?limit=10", MESSAGE_SERVICE, CONVERSATION_SERVICE);
        expectRoutedTo("POST", "/api/conversations/" + id + "/read", MESSAGE_SERVICE, CONVERSATION_SERVICE);
    }

    @Test
    void conversationEndpointsGoToConversationService() {
        UUID id = UUID.randomUUID();
        expectRoutedTo("POST", "/api/conversations", CONVERSATION_SERVICE, MESSAGE_SERVICE);
        expectRoutedTo("GET", "/api/conversations", CONVERSATION_SERVICE, MESSAGE_SERVICE);
        expectRoutedTo("GET", "/api/conversations/" + id, CONVERSATION_SERVICE, MESSAGE_SERVICE);
    }

    @Test
    void messageAndConversationEndpointsNeedAToken() {
        client.get().uri("/api/messages/summaries").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/messages").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/conversations").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/conversations/" + UUID.randomUUID() + "/messages").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void internalEndpointsAreNeverRoutedToTheOutside() {
        client.get().uri("/internal/conversations/" + UUID.randomUUID())
                .header("Authorization", bearer())
                .exchange().expectStatus().isNotFound();
        client.get().uri("/internal/blocks?a=x&b=y").exchange().expectStatus().isNotFound();
    }

    @Test
    void mediaUploadsGoToMediaServiceAndNeedAToken() {
        // a 3 MB body must pass through (the gateway streams it, it does not buffer or limit it to a few hundred KB)
        byte[] file = new byte[3 * 1024 * 1024];
        client.post().uri("/api/media/upload")
                .header("Authorization", bearer())
                .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(file)
                .exchange()
                .expectStatus().isOk();
        assertThat(MEDIA_SERVICE.lastPath.get()).isEqualTo("/api/media/upload");

        client.post().uri("/api/media/upload").bodyValue(file).exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/media/" + UUID.randomUUID()).exchange().expectStatus().isUnauthorized();
        expectRoutedTo("GET", "/api/media/" + UUID.randomUUID(), MEDIA_SERVICE, MESSAGE_SERVICE, CONVERSATION_SERVICE);
    }

    @Test
    void presenceAndNotificationEndpointsGoToTheirServicesAndNeedAToken() {
        expectRoutedTo("POST", "/api/presence/heartbeat", PRESENCE_SERVICE, NOTIFICATION_SERVICE, MESSAGE_SERVICE);
        expectRoutedTo("GET", "/api/presence/" + UUID.randomUUID(), PRESENCE_SERVICE, NOTIFICATION_SERVICE);
        expectRoutedTo("GET", "/api/notifications/unread-count", NOTIFICATION_SERVICE, PRESENCE_SERVICE);
        expectRoutedTo("PUT", "/api/notifications/preferences", NOTIFICATION_SERVICE, PRESENCE_SERVICE);

        client.post().uri("/api/presence/heartbeat").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/notifications").exchange().expectStatus().isUnauthorized();
        // internal endpoints of these services are not reachable from outside either
        client.get().uri("/internal/presence/online?userIds=" + UUID.randomUUID())
                .header("Authorization", bearer()).exchange().expectStatus().isNotFound();
    }

    @Test
    void callEndpointsGoToCallSignalingServiceAndNeedAToken() {
        UUID id = UUID.randomUUID();
        expectRoutedTo("POST", "/api/calls", CALL_SERVICE, MESSAGE_SERVICE, NOTIFICATION_SERVICE);
        expectRoutedTo("GET", "/api/calls/active", CALL_SERVICE, MESSAGE_SERVICE);
        expectRoutedTo("GET", "/api/calls/history?page=0&size=20", CALL_SERVICE, MESSAGE_SERVICE);
        expectRoutedTo("POST", "/api/calls/" + id + "/accept", CALL_SERVICE, MESSAGE_SERVICE);
        expectRoutedTo("POST", "/api/calls/" + id + "/signal", CALL_SERVICE, MESSAGE_SERVICE);
        expectRoutedTo("POST", "/api/calls/" + id + "/ping", CALL_SERVICE, MESSAGE_SERVICE);

        client.post().uri("/api/calls").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/calls/history").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/calls/" + id + "/signal").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void aCrashedCallServiceGivesTheStandard503FromItsOwnBreaker() {
        CALL_SERVICE.broken = true;
        try {
            client.post().uri("/api/calls/" + UUID.randomUUID() + "/end")
                    .header("Authorization", bearer())
                    .exchange()
                    .expectStatus().isEqualTo(503)
                    .expectBody()
                    .jsonPath("$.success").isEqualTo(false);
        } finally {
            CALL_SERVICE.broken = false;
        }
        // the other services are not affected by the call service's breaker
        expectRoutedTo("GET", "/api/notifications/unread-count", NOTIFICATION_SERVICE, CALL_SERVICE);
        expectRoutedTo("GET", "/api/calls/active", CALL_SERVICE, NOTIFICATION_SERVICE);
    }

    @Test
    void statusEndpointsGoToStatusServiceAndNeedAToken() {
        UUID id = UUID.randomUUID();
        expectRoutedTo("POST", "/api/statuses", STATUS_SERVICE, CALL_SERVICE, MEDIA_SERVICE);
        expectRoutedTo("GET", "/api/statuses/feed", STATUS_SERVICE, CALL_SERVICE);
        expectRoutedTo("GET", "/api/statuses/mine", STATUS_SERVICE, CALL_SERVICE);
        expectRoutedTo("POST", "/api/statuses/" + id + "/view", STATUS_SERVICE, CALL_SERVICE);
        expectRoutedTo("GET", "/api/statuses/" + id + "/viewers", STATUS_SERVICE, CALL_SERVICE);
        expectRoutedTo("GET", "/api/statuses/" + id + "/media-url?variant=thumbnail", STATUS_SERVICE, MEDIA_SERVICE);
        expectRoutedTo("DELETE", "/api/statuses/" + id, STATUS_SERVICE, CALL_SERVICE);
        expectRoutedTo("PUT", "/api/statuses/privacy", STATUS_SERVICE, CALL_SERVICE);

        client.get().uri("/api/statuses/feed").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/statuses").exchange().expectStatus().isUnauthorized();
        client.delete().uri("/api/statuses/" + id).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void aCrashedStatusServiceGivesTheStandard503FromItsOwnBreaker() {
        STATUS_SERVICE.broken = true;
        try {
            client.get().uri("/api/statuses/feed")
                    .header("Authorization", bearer())
                    .exchange()
                    .expectStatus().isEqualTo(503)
                    .expectBody()
                    .jsonPath("$.success").isEqualTo(false);
        } finally {
            STATUS_SERVICE.broken = false;
        }
        // the other services are not affected by the status service's breaker
        expectRoutedTo("GET", "/api/calls/active", CALL_SERVICE, STATUS_SERVICE);
        expectRoutedTo("GET", "/api/statuses/mine", STATUS_SERVICE, CALL_SERVICE);
    }

    @Test
    void webSocketHandshakeNeedsNoTokenAtTheGateway() {
        // The target is not reachable in this test, so the answer is an error, but it must NOT be 401:
        // the token is checked inside the STOMP CONNECT frame by message-service.
        int status = client.get().uri("/ws")
                .exchange()
                .returnResult(String.class)
                .getStatus().value();
        assertThat(status).isNotEqualTo(401);
    }

    @Test
    void swaggerDocsOfAllServicesAreReachable() {
        client.get().uri("/service-docs/conversation").exchange().expectStatus().isOk();
        assertThat(CONVERSATION_SERVICE.lastPath.get()).isEqualTo("/v3/api-docs");
        client.get().uri("/service-docs/message").exchange().expectStatus().isOk();
        assertThat(MESSAGE_SERVICE.lastPath.get()).isEqualTo("/v3/api-docs");
        client.get().uri("/service-docs/media").exchange().expectStatus().isOk();
        assertThat(MEDIA_SERVICE.lastPath.get()).isEqualTo("/v3/api-docs");
        client.get().uri("/service-docs/presence").exchange().expectStatus().isOk();
        assertThat(PRESENCE_SERVICE.lastPath.get()).isEqualTo("/v3/api-docs");
        client.get().uri("/service-docs/notification").exchange().expectStatus().isOk();
        assertThat(NOTIFICATION_SERVICE.lastPath.get()).isEqualTo("/v3/api-docs");
        client.get().uri("/service-docs/call").exchange().expectStatus().isOk();
        assertThat(CALL_SERVICE.lastPath.get()).isEqualTo("/v3/api-docs");
        client.get().uri("/service-docs/status").exchange().expectStatus().isOk();
        assertThat(STATUS_SERVICE.lastPath.get()).isEqualTo("/v3/api-docs");
    }
}
