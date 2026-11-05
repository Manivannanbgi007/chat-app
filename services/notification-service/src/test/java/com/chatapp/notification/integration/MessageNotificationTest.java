package com.chatapp.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.chatapp.notification.service.NotificationEventService;
import com.chatapp.notification.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/** The main flow: message.events in, "new messages" notifications out. */
class MessageNotificationTest extends AbstractIntegrationTest {

    private static final String TOPIC = "message.events";

    @Autowired
    private NotificationEventService eventService;

    private UUID alice;
    private UUID bob;
    private UUID chat;

    @BeforeEach
    void setUp() {
        alice = newId();
        bob = newId();
        chat = newId();
        PEERS.addPrivateChat(chat);
        PEERS.setName(alice, "Alice");
        PEERS.presenceDown = false;
        PEERS.conversationDown = false;
        PEERS.userDown = false;
    }

    @Test
    void offlineRecipientGetsOneNotificationWithTheSenderName() {
        UUID eventId = messageCreated(chat, alice, "TEXT", List.of(alice, bob));
        awaitHandled(eventId);

        List<Map<String, Object>> rows = notificationsOf(bob);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("type", "NEW_MESSAGES").containsEntry("title", "Alice")
                .containsEntry("body", "1 new message").containsEntry("item_count", 1);
        assertThat(rows.get(0).get("conversation_id")).hasToString(chat.toString());
        assertThat(notificationCount(alice)).as("the sender gets nothing").isZero();
    }

    @Test
    void onlineRecipientGetsNothing() {
        PEERS.setOnline(bob);
        try {
            UUID eventId = messageCreated(chat, alice, "TEXT", List.of(bob));
            awaitHandled(eventId);
            assertThat(notificationCount(bob)).isZero();
        } finally {
            PEERS.setOffline(bob);
        }
    }

    @Test
    void mutedRecipientGetsNothing() {
        PEERS.mute(chat, bob);
        UUID eventId = messageCreated(chat, alice, "TEXT", List.of(bob));
        awaitHandled(eventId);
        assertThat(notificationCount(bob)).isZero();
    }

    @Test
    void privateChatsFollowMessagesEnabledAndGroupsFollowGroupsEnabled() throws Exception {
        setPreferences(bob, true, false, true);       // no private messages, groups yes
        UUID group = newId();
        PEERS.addGroup(group, "Trip to Goa");

        awaitHandled(messageCreated(chat, alice, "TEXT", List.of(bob)));
        awaitHandled(messageCreated(group, alice, "TEXT", List.of(bob)));
        assertThat(notificationsOf(bob)).hasSize(1);
        assertThat(notificationsOf(bob).get(0)).containsEntry("title", "Trip to Goa");

        UUID carol = newId();
        setPreferences(carol, true, true, false);     // private yes, groups no
        awaitHandled(messageCreated(chat, alice, "TEXT", List.of(carol)));
        awaitHandled(messageCreated(group, alice, "TEXT", List.of(carol)));
        assertThat(notificationsOf(carol)).hasSize(1);
        assertThat(notificationsOf(carol).get(0)).containsEntry("title", "Alice");
    }

    @Test
    void threeMessagesBecomeOneNotificationThatSaysThree() {
        UUID last = null;
        for (int i = 0; i < 3; i++) {
            last = messageCreated(chat, alice, "TEXT", List.of(bob));
        }
        awaitHandled(last);

        List<Map<String, Object>> rows = notificationsOf(bob);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("item_count", 3).containsEntry("body", "3 new messages");
    }

    @Test
    void theSameEventDeliveredTwiceIsCountedOnce() {
        UUID eventId = newId();
        String json = event(eventId, "MessageCreatedEvent", chat, Map.of(
                "messageId", newId().toString(), "conversationId", chat.toString(), "senderId", alice.toString(),
                "type", "TEXT", "recipientIds", List.of(bob.toString())));
        publish(TOPIC, chat, json);
        publish(TOPIC, chat, json);
        flush(TOPIC, chat);

        assertThat(notificationsOf(bob)).hasSize(1);
        assertThat(notificationsOf(bob).get(0)).containsEntry("item_count", 1);
    }

    @Test
    void systemMessagesAreIgnored() {
        messageCreated(chat, alice, "SYSTEM", List.of(bob));
        flush(TOPIC, chat);
        assertThat(notificationCount(bob)).isZero();
    }

    @Test
    void messagesOfAnUnknownConversationAreIgnored() {
        UUID unknownChat = newId();
        messageCreated(unknownChat, alice, "TEXT", List.of(bob));
        flush(TOPIC, unknownChat);
        assertThat(notificationCount(bob)).isZero();
    }

    @Test
    void readingTheChatMarksItReadAndTheNextMessageStartsANewNotification() throws Exception {
        awaitHandled(messageCreated(chat, alice, "TEXT", List.of(bob)));
        awaitHandled(messageCreated(chat, alice, "TEXT", List.of(bob)));
        assertThat(notificationsOf(bob)).hasSize(1);

        messageRead(chat, alice, bob);
        flush(TOPIC, chat);
        assertThat(notificationsOf(bob).get(0)).containsEntry("is_read", true);
        assertThat(read(getAs(bob, "/api/notifications/unread-count"), "$.data.unreadCount").toString()).isEqualTo("0");

        awaitHandled(messageCreated(chat, alice, "TEXT", List.of(bob)));
        List<Map<String, Object>> rows = notificationsOf(bob);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("item_count", 2).containsEntry("is_read", true);
        assertThat(rows.get(1)).containsEntry("item_count", 1).containsEntry("is_read", false);
    }

    @Test
    void groupMessagesUseTheGroupNameAsTitle() {
        UUID group = newId();
        PEERS.addGroup(group, "Weekend plan");
        awaitHandled(messageCreated(group, alice, "TEXT", List.of(bob)));
        assertThat(notificationsOf(bob).get(0)).containsEntry("title", "Weekend plan");
    }

    @Test
    void unknownSenderNameFallsBackToSomeone() {
        UUID stranger = newId();       // user-service does not know this person
        awaitHandled(messageCreated(chat, stranger, "TEXT", List.of(bob)));
        assertThat(notificationsOf(bob).get(0)).containsEntry("title", "Someone");
    }

    @Test
    void userServiceBeingDownDoesNotStopTheNotification() {
        PEERS.userDown = true;
        UUID otherSender = newId();
        awaitHandled(messageCreated(chat, otherSender, "TEXT", List.of(bob)));
        assertThat(notificationsOf(bob).get(0)).containsEntry("title", "Someone");
    }

    @Test
    void presenceBeingDownTreatsEverybodyAsOffline() {
        PEERS.setOnline(bob);
        PEERS.presenceDown = true;
        try {
            awaitHandled(messageCreated(chat, alice, "TEXT", List.of(bob)));
            assertThat(notificationCount(bob)).isEqualTo(1);
        } finally {
            PEERS.setOffline(bob);
        }
    }

    @Test
    void oneEventCostsOnePresenceCall() {
        List<UUID> recipients = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            recipients.add(newId());
        }
        int before = PEERS.presenceQueries.size();
        UUID eventId = messageCreated(chat, alice, "TEXT", recipients);
        awaitHandled(eventId);
        long calls = PEERS.presenceQueries.stream().skip(before).filter(q -> q.contains(recipients.get(0).toString())).count();
        assertThat(calls).isEqualTo(1);
    }

    @Test
    void messageTextNeverAppearsAnywhere() throws Exception {
        String secret = "TOP-SECRET-TEXT-" + UUID.randomUUID();
        UUID eventId = newId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", newId().toString());
        payload.put("conversationId", chat.toString());
        payload.put("senderId", alice.toString());
        payload.put("type", "TEXT");
        payload.put("recipientIds", List.of(bob.toString()));
        payload.put("text", secret);          // message-service never sends it, but even if it did we must not keep it
        payload.put("content", secret);

        try (LiveListener live = new LiveListener()) {
            publish(TOPIC, chat, event(eventId, "MessageCreatedEvent", chat, payload));
            awaitHandled(eventId);
            eventually(() -> !live.forUser(bob).isEmpty(), "live event for bob");

            assertThat(live.forUser(bob)).allSatisfy(message -> assertThat(message).doesNotContain(secret));
        }
        assertThat(notificationsOf(bob).toString()).doesNotContain(secret);
        assertThat(deliveriesOf(bob).toString()).doesNotContain(secret);
        assertThat(getAs(bob, "/api/notifications").getResponse().getContentAsString()).doesNotContain(secret);
    }

    @Test
    void aLiveEventIsPublishedToRedisForTheRecipient() throws Exception {
        try (LiveListener live = new LiveListener()) {
            awaitHandled(messageCreated(chat, alice, "TEXT", List.of(bob)));
            eventually(() -> !live.forUser(bob).isEmpty(), "live event for bob");

            String envelope = live.forUser(bob).get(0);
            assertThat(com.jayway.jsonpath.JsonPath.<List<String>>read(envelope, "$.userIds")).containsExactly(bob.toString());
            assertThat(com.jayway.jsonpath.JsonPath.<String>read(envelope, "$.destination")).isEqualTo("/queue/notifications");
            String inner = com.jayway.jsonpath.JsonPath.read(envelope, "$.json");
            assertThat(com.jayway.jsonpath.JsonPath.<String>read(inner, "$.type")).isEqualTo("NOTIFICATION");
            assertThat(com.jayway.jsonpath.JsonPath.<String>read(inner, "$.data.title")).isEqualTo("Alice");
            assertThat(com.jayway.jsonpath.JsonPath.<String>read(inner, "$.data.body")).isEqualTo("1 new message");
            assertThat(com.jayway.jsonpath.JsonPath.<String>read(inner, "$.conversationId")).isEqualTo(chat.toString());
        }
    }

    @Test
    void messagesFromManyThreadsAreAllCountedInOneRow() throws Exception {
        int events = 12;
        ExecutorService pool = Executors.newFixedThreadPool(events);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < events; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                eventService.onMessageCreated(newId(), chat, alice, List.of(bob));
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        List<Map<String, Object>> rows = notificationsOf(bob);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("item_count", events).containsEntry("body", events + " new messages");
    }

    @Test
    void theApiShowsTheNotification() throws Exception {
        awaitHandled(messageCreated(chat, alice, "TEXT", List.of(bob)));
        MvcResult result = getAs(bob, "/api/notifications");
        assertStatus(result, 200);
        assertThat(field(result, "$.data.items[0].title")).isEqualTo("Alice");
        assertThat(field(result, "$.data.items[0].count")).isEqualTo("1");
        assertThat(field(result, "$.data.items[0].read")).isEqualTo("false");
    }
}
