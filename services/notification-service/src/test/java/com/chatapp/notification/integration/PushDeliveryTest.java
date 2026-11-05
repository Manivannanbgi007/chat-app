package com.chatapp.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chatapp.notification.push.PushDeliveryJob;
import com.chatapp.notification.push.PushSender.PushMessage;
import com.chatapp.notification.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The push queue: rows are created with the notification, PushDeliveryJob sends them, failures are retried. */
class PushDeliveryTest extends AbstractIntegrationTest {

    @Autowired
    private PushDeliveryJob job;

    private UUID alice;
    private UUID bob;
    private UUID chat;

    @BeforeEach
    void setUp() {
        pushSender.reset();
        alice = newId();
        bob = newId();
        chat = newId();
        PEERS.addPrivateChat(chat);
        PEERS.setName(alice, "Alice");
    }

    private List<PushMessage> sentTo(UUID user) {
        return pushSender.sent.stream().filter(m -> m.userId().equals(user)).toList();
    }

    private void makeDueNow(UUID user) {
        jdbc.update("UPDATE notification_delivery_logs SET next_attempt_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE user_id = ?",
                user.toString());
    }

    @Test
    void aPushRowIsQueuedOnlyForOfflineUsersWithATokenAndPushEnabled() throws Exception {
        UUID noToken = newId();
        UUID pushOff = newId();
        UUID online = newId();
        registerDevice(bob, newToken());
        registerDevice(pushOff, newToken());
        registerDevice(online, newToken());
        setPreferences(pushOff, false, true, true);
        PEERS.setOnline(online);
        try {
            awaitHandled(messageCreated(chat, alice, "TEXT", List.of(bob, noToken, pushOff, online)));
        } finally {
            PEERS.setOffline(online);
        }

        assertThat(deliveriesOf(bob)).hasSize(1);
        assertThat(deliveriesOf(bob).get(0)).containsEntry("status", "PENDING").containsEntry("channel", "PUSH");
        assertThat(deliveriesOf(noToken)).isEmpty();
        assertThat(deliveriesOf(pushOff)).isEmpty();
        assertThat(deliveriesOf(online)).isEmpty();
        assertThat(notificationCount(noToken)).as("the notification itself is still created").isEqualTo(1);
        assertThat(notificationCount(pushOff)).isEqualTo(1);
    }

    @Test
    void theJobSendsAPushWithTheTitleAndBodyAndMarksItSent() throws Exception {
        String token = newToken();
        registerDevice(bob, token);
        awaitHandled(messageCreated(chat, alice, "TEXT", List.of(bob)));

        job.sendDue();

        assertThat(sentTo(bob)).hasSize(1);
        PushMessage push = sentTo(bob).get(0);
        assertThat(push.tokens()).containsExactly(token);
        assertThat(push.title()).isEqualTo("Alice");
        assertThat(push.body()).isEqualTo("1 new message");
        assertThat(push.conversationId()).isEqualTo(chat);
        assertThat(deliveriesOf(bob).get(0)).containsEntry("status", "SENT").containsEntry("attempts", 1);

        job.sendDue();
        assertThat(sentTo(bob)).as("a sent push is not sent again").hasSize(1);
    }

    @Test
    void aPushIsSentToEveryDeviceOfTheUser() throws Exception {
        String phone = newToken();
        String laptop = newToken();
        registerDevice(bob, phone);
        registerDevice(bob, laptop);
        awaitHandled(messageCreated(chat, alice, "TEXT", List.of(bob)));
        job.sendDue();
        assertThat(sentTo(bob)).hasSize(1);
        assertThat(sentTo(bob).get(0).tokens()).containsExactlyInAnyOrder(phone, laptop);
    }

    @Test
    void aFailingPushIsRetriedLaterAndThenGivenUp() throws Exception {
        registerDevice(bob, newToken());
        awaitHandled(messageCreated(chat, alice, "TEXT", List.of(bob)));
        pushSender.failing = true;

        job.sendDue();
        Map<String, Object> first = deliveriesOf(bob).get(0);
        assertThat(first).containsEntry("status", "FAILED").containsEntry("attempts", 1);
        assertThat(first.get("last_error")).isEqualTo("IllegalStateException");
        Long inTheFuture = jdbc.queryForObject(
                "SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(6), next_attempt_at) FROM notification_delivery_logs WHERE user_id = ?",
                Long.class, bob.toString());
        assertThat(inTheFuture).as("first retry after about 10 seconds").isBetween(5L, 11L);

        job.sendDue();
        assertThat(deliveriesOf(bob).get(0)).as("not due yet, so untouched").containsEntry("attempts", 1);

        makeDueNow(bob);
        job.sendDue();
        Map<String, Object> second = deliveriesOf(bob).get(0);
        assertThat(second).containsEntry("status", "FAILED").containsEntry("attempts", 2);
        Long later = jdbc.queryForObject(
                "SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(6), next_attempt_at) FROM notification_delivery_logs WHERE user_id = ?",
                Long.class, bob.toString());
        assertThat(later).as("second retry after about a minute").isBetween(50L, 61L);

        makeDueNow(bob);
        job.sendDue();
        assertThat(deliveriesOf(bob).get(0)).containsEntry("status", "DEAD").containsEntry("attempts", 3);

        makeDueNow(bob);
        pushSender.failing = false;
        job.sendDue();
        assertThat(sentTo(bob)).as("a DEAD push is never sent").isEmpty();
    }

    @Test
    void aFailedPushCanSucceedOnTheRetry() throws Exception {
        registerDevice(bob, newToken());
        awaitHandled(messageCreated(chat, alice, "TEXT", List.of(bob)));
        pushSender.failing = true;
        job.sendDue();
        pushSender.failing = false;
        makeDueNow(bob);
        job.sendDue();

        assertThat(deliveriesOf(bob).get(0)).containsEntry("status", "SENT").containsEntry("attempts", 2);
        assertThat(deliveriesOf(bob).get(0).get("last_error")).isNull();
        assertThat(sentTo(bob)).hasSize(1);
    }

    @Test
    void theErrorTextWithATokenInItIsNeverStored() throws Exception {
        registerDevice(bob, newToken());
        awaitHandled(messageCreated(chat, alice, "TEXT", List.of(bob)));
        pushSender.failing = true;     // its exception text contains "tok-123456"
        job.sendDue();
        assertThat(deliveriesOf(bob).toString()).doesNotContain("tok-123456").doesNotContain("provider is down");
    }

    @Test
    void ifTheTokensAreGoneWhenTheJobRunsNothingIsSent() throws Exception {
        String token = newToken();
        registerDevice(bob, token);
        awaitHandled(messageCreated(chat, alice, "TEXT", List.of(bob)));
        assertStatus(deleteAs(bob, "/api/notifications/device-tokens?token=" + token), 200);

        job.sendDue();

        assertThat(sentTo(bob)).isEmpty();
        assertThat(deliveriesOf(bob).get(0)).containsEntry("status", "SENT").containsEntry("last_error", "no device tokens");
    }

    @Test
    void twoMessagesBeforeTheJobRunsAreOnePushWithTheLatestText() throws Exception {
        registerDevice(bob, newToken());
        awaitHandled(messageCreated(chat, alice, "TEXT", List.of(bob)));
        awaitHandled(messageCreated(chat, alice, "TEXT", List.of(bob)));
        assertThat(deliveriesOf(bob)).hasSize(1);

        job.sendDue();
        assertThat(sentTo(bob)).hasSize(1);
        assertThat(sentTo(bob).get(0).body()).isEqualTo("2 new messages");

        awaitHandled(messageCreated(chat, alice, "TEXT", List.of(bob)));
        assertThat(deliveriesOf(bob)).as("the first push was sent, so the third message needs a new one").hasSize(2);
        job.sendDue();
        assertThat(sentTo(bob)).hasSize(2);
        assertThat(sentTo(bob).get(1).body()).isEqualTo("3 new messages");
    }
}
