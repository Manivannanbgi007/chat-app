package com.chatapp.message.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;

import com.chatapp.message.support.AbstractIntegrationTest;
import com.chatapp.message.support.TestJwt;
import com.chatapp.message.support.TestStompClient;
import com.chatapp.message.websocket.SessionExpiryChecker;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MvcResult;

/** Real STOMP clients against the running application (random port): security rules and live delivery. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketTest extends AbstractIntegrationTest {

    private static final long WAIT_MS = 8_000;

    @LocalServerPort
    private int port;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private SessionExpiryChecker expiryChecker;

    private final java.util.List<TestStompClient> clients = new java.util.ArrayList<>();

    @AfterEach
    void closeClients() {
        clients.forEach(TestStompClient::close);
        clients.clear();
        PEERS.unblockAll();
    }

    private TestStompClient connect(Device device) throws Exception {
        TestStompClient client = TestStompClient.connect(port, device.token());
        clients.add(client);
        return client;
    }

    private String json(Object body) {
        return jsonMapper.writeValueAsString(body);
    }

    private String next(BlockingQueue<String> queue) throws Exception {
        return TestStompClient.next(queue, WAIT_MS);
    }

    /** Subscribing is asynchronous; a short pause makes sure the server registered it before we send. */
    private void settle() throws InterruptedException {
        Thread.sleep(400);
    }

    // ------------------------------------------------------------ authentication

    @Test
    void validTokenConnects() throws Exception {
        TestStompClient client = connect(Device.newUser());
        assertThat(client.isConnected()).isTrue();
    }

    @Test
    void connectWithoutTokenIsRefused() {
        assertThatThrownBy(() -> TestStompClient.connect(port, null)).isNotNull();
    }

    @Test
    void connectWithBadTokensIsRefused() {
        Device device = Device.newUser();
        String wrongSecret = TestJwt.create("another-secret-another-secret-another-123", "chat-app-auth",
                device.userId(), device.sessionId(), Instant.now().plusSeconds(600));
        String expired = TestJwt.create(device.userId(), device.sessionId(), Instant.now().minusSeconds(300));
        String wrongIssuer = TestJwt.create(TestJwt.SECRET, "someone-else", device.userId(), device.sessionId(),
                Instant.now().plusSeconds(600));

        for (String token : new String[]{wrongSecret, expired, wrongIssuer, "not.a.jwt"}) {
            assertThatThrownBy(() -> TestStompClient.connect(port, token)).isNotNull();
        }
    }

    @Test
    void connectWithARevokedSessionIsRefused() {
        Device device = Device.newUser();
        redis.opsForValue().set("auth:revoked:" + device.sessionId(), "1", Duration.ofMinutes(5));

        assertThatThrownBy(() -> TestStompClient.connect(port, device.token())).isNotNull();
    }

    // ------------------------------------------------------------- authorization

    @Test
    void cannotSubscribeToAnotherUsersQueueOrUnknownDestinations() throws Exception {
        Device alice = Device.newUser();
        for (String destination : List.of(
                "/user/" + UUID.randomUUID() + "/queue/messages",
                "/queue/messages",
                "/topic/anything",
                "/user/queue/other")) {
            TestStompClient client = connect(alice);
            client.subscribe(destination);
            assertThat(client.waitUntilClosed(WAIT_MS)).as("destination " + destination).isTrue();
        }
    }

    @Test
    void conversationTopicsCanNoLongerBeSubscribedEvenByParticipants() throws Exception {
        // Phase 4: typing moved to /user/queue/typing. A topic subscription would outlive a removal from a group.
        Chat chat = newChat();

        TestStompClient participant = connect(chat.a());
        participant.subscribe("/topic/conversation/" + chat.id());
        assertThat(participant.waitUntilClosed(WAIT_MS)).isTrue();
    }

    @Test
    void everyOwnQueueCanBeSubscribed() throws Exception {
        TestStompClient client = connect(Device.newUser());
        for (String queue : List.of("/user/queue/messages", "/user/queue/receipts", "/user/queue/acks",
                "/user/queue/errors", "/user/queue/typing", "/user/queue/notifications", "/user/queue/calls")) {
            client.subscribe(queue);
        }
        settle();
        assertThat(client.isConnected()).isTrue();
    }

    @Test
    void cannotSendToDestinationsOutsideAppChat() throws Exception {
        TestStompClient client = connect(Device.newUser());
        client.send("/app/admin.something", "{}");
        assertThat(client.waitUntilClosed(WAIT_MS)).isTrue();

        TestStompClient second = connect(Device.newUser());
        second.send("/topic/conversation/" + UUID.randomUUID(), "{}");
        assertThat(second.waitUntilClosed(WAIT_MS)).isTrue();
    }

    // ----------------------------------------------------------- live delivery

    @Test
    void recipientAndSenderDevicesReceiveANewMessageLive() throws Exception {
        Chat chat = newChat();
        TestStompClient bobPhone = connect(chat.b());
        TestStompClient bobLaptop = connect(chat.b().otherDevice());
        TestStompClient aliceOtherDevice = connect(chat.a().otherDevice());
        TestStompClient carol = connect(Device.newUser());               // not in the conversation
        BlockingQueue<String> phone = bobPhone.subscribe("/user/queue/messages");
        BlockingQueue<String> laptop = bobLaptop.subscribe("/user/queue/messages");
        BlockingQueue<String> alice = aliceOtherDevice.subscribe("/user/queue/messages");
        BlockingQueue<String> stranger = carol.subscribe("/user/queue/messages");
        settle();

        UUID id = sendOk(chat.a(), chat.id(), "hello live");

        for (BlockingQueue<String> queue : List.of(phone, laptop, alice)) {
            String event = next(queue);
            assertThat((String) JsonPath.read(event, "$.type")).isEqualTo("MESSAGE_CREATED");
            assertThat((String) JsonPath.read(event, "$.conversationId")).isEqualTo(chat.id().toString());
            assertThat((String) JsonPath.read(event, "$.data.id")).isEqualTo(id.toString());
            assertThat((String) JsonPath.read(event, "$.data.content")).isEqualTo("hello live");
            assertThat((String) JsonPath.read(event, "$.data.senderId")).isEqualTo(chat.a().userId().toString());
        }
        Thread.sleep(500);
        assertThat(stranger).as("a user outside the conversation receives nothing").isEmpty();
    }

    @Test
    void aGroupMessageReachesEveryDeviceOfEveryCurrentMemberAndNobodyElse() throws Exception {
        Group group = newGroup(2);
        Device bob = group.member(0);
        Device carol = group.member(1);
        Device dave = Device.newUser();
        PEERS.addMember(group.id(), dave.userId());
        PEERS.removeMember(group.id(), dave.userId());               // Dave was in the group, but not any more

        BlockingQueue<String> bobPhone = connect(bob).subscribe("/user/queue/messages");
        BlockingQueue<String> bobLaptop = connect(bob.otherDevice()).subscribe("/user/queue/messages");
        BlockingQueue<String> carolQueue = connect(carol).subscribe("/user/queue/messages");
        BlockingQueue<String> adminOther = connect(group.admin().otherDevice()).subscribe("/user/queue/messages");
        BlockingQueue<String> daveQueue = connect(dave).subscribe("/user/queue/messages");
        BlockingQueue<String> strangerQueue = connect(Device.newUser()).subscribe("/user/queue/messages");
        settle();

        UUID id = sendOk(group.admin(), group.id(), "hello group");

        for (BlockingQueue<String> queue : List.of(bobPhone, bobLaptop, carolQueue, adminOther)) {
            String event = next(queue);
            assertThat((String) JsonPath.read(event, "$.data.id")).isEqualTo(id.toString());
            assertThat((String) JsonPath.read(event, "$.data.senderId")).isEqualTo(group.admin().userId().toString());
        }
        Thread.sleep(500);
        assertThat(daveQueue).as("a former member").isEmpty();
        assertThat(strangerQueue).as("a stranger").isEmpty();
    }

    @Test
    void groupSenderIsToldOnlyWhenEveryRecipientHasTheMessage() throws Exception {
        Group group = newGroup(2);
        UUID message = sendOk(group.admin(), group.id(), "hello both");
        BlockingQueue<String> receipts = connect(group.admin()).subscribe("/user/queue/receipts");
        settle();

        markDelivered(group.member(0), message);
        Thread.sleep(700);
        assertThat(receipts).as("one of two recipients: the ticks do not change yet").isEmpty();

        markDelivered(group.member(1), message);
        String delivered = next(receipts);
        assertThat((String) JsonPath.read(delivered, "$.type")).isEqualTo("RECEIPT_UPDATED");
        assertThat((String) JsonPath.read(delivered, "$.data.messageId")).isEqualTo(message.toString());
        assertThat((String) JsonPath.read(delivered, "$.data.status")).isEqualTo("DELIVERED");

        markRead(group.member(0), group.id(), message);
        String stillDelivered = next(receipts);
        assertThat((String) JsonPath.read(stillDelivered, "$.data.status")).isEqualTo("DELIVERED");
        markRead(group.member(1), group.id(), message);
        assertThat((String) JsonPath.read(next(receipts), "$.data.status")).isEqualTo("READ");
    }

    @Test
    void aRemovedMemberReceivesTheSystemMessageLive() throws Exception {
        Device admin = Device.newUser();
        Device bob = Device.newUser();
        Device carol = Device.newUser();
        UUID groupId = UUID.randomUUID();
        BlockingQueue<String> carolQueue = connect(carol).subscribe("/user/queue/messages");
        BlockingQueue<String> bobQueue = connect(bob).subscribe("/user/queue/messages");
        settle();

        Instant createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        publishEvent("ConversationCreatedEvent", groupId, Map.of(
                "type", "GROUP",
                "participantIds", List.of(admin.userId().toString(), bob.userId().toString(), carol.userId().toString()),
                "createdAt", createdAt.toString(), "createdBy", admin.userId().toString(),
                "groupName", "Live group", "createdByName", "Asha"));
        // Kafka-driven: allow more time (a consumer group may be rebalancing when this test class starts)
        assertThat((String) JsonPath.read(TestStompClient.next(carolQueue, 30_000), "$.data.type")).isEqualTo("SYSTEM");
        TestStompClient.next(bobQueue, 30_000);

        publishEvent("GroupMemberRemovedEvent", groupId, Map.of(
                "actorId", admin.userId().toString(), "actorName", "Asha",
                "userId", carol.userId().toString(), "userName", "Carol",
                "reason", "REMOVED", "leftAt", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString()));

        for (BlockingQueue<String> queue : List.of(carolQueue, bobQueue)) {
            String event = TestStompClient.next(queue, 30_000);
            assertThat((String) JsonPath.read(event, "$.type")).isEqualTo("MESSAGE_CREATED");
            assertThat((String) JsonPath.read(event, "$.conversationId")).isEqualTo(groupId.toString());
            assertThat((String) JsonPath.read(event, "$.data.type")).isEqualTo("SYSTEM");
            assertThat((Object) JsonPath.read(event, "$.data.status")).isNull();
            String content = JsonPath.read(event, "$.data.content");
            assertThat((String) JsonPath.read(content, "$.event")).isEqualTo("MEMBER_REMOVED");
        }
    }

    @Test
    void eventFromAnotherInstanceReachesALocalSocketThroughRedis() throws Exception {
        Device bob = Device.newUser();
        TestStompClient client = connect(bob);
        BlockingQueue<String> queue = client.subscribe("/user/queue/messages");
        settle();

        // what another message-service instance would publish
        String eventJson = "{\"type\":\"MESSAGE_CREATED\",\"data\":{\"content\":\"from another instance\"}}";
        String envelope = json(Map.of("userIds", List.of(bob.userId()), "destination", "/queue/messages", "json", eventJson));
        redis.convertAndSend("ws.events", envelope);

        assertThat((String) JsonPath.read(next(queue), "$.data.content")).isEqualTo("from another instance");
    }

    @Test
    void notificationFromTheNotificationServiceReachesTheUsersSocket() throws Exception {
        Device bob = Device.newUser();
        Device someoneElse = Device.newUser();
        TestStompClient client = connect(bob);
        TestStompClient other = connect(someoneElse);
        BlockingQueue<String> queue = client.subscribe("/user/queue/notifications");
        BlockingQueue<String> otherQueue = other.subscribe("/user/queue/notifications");
        settle();

        // what notification-service publishes into the shared channel (same envelope as message-service)
        String eventJson = "{\"type\":\"NOTIFICATION\",\"data\":{\"title\":\"Alice\",\"count\":2}}";
        redis.convertAndSend("ws.events", json(Map.of("userIds", List.of(bob.userId()),
                "destination", "/queue/notifications", "json", eventJson)));

        String received = next(queue);
        assertThat((String) JsonPath.read(received, "$.type")).isEqualTo("NOTIFICATION");
        assertThat((Integer) JsonPath.read(received, "$.data.count")).isEqualTo(2);
        Thread.sleep(400);
        assertThat(otherQueue).as("other users get nothing").isEmpty();
    }

    @Test
    void callEventFromTheCallSignalingServiceReachesOnlyThatUsersSocket() throws Exception {
        Device callee = Device.newUser();
        Device calleeSecondDevice = callee.otherDevice();
        Device someoneElse = Device.newUser();
        TestStompClient phone = connect(callee);
        TestStompClient laptop = connect(calleeSecondDevice);
        TestStompClient other = connect(someoneElse);
        BlockingQueue<String> phoneCalls = phone.subscribe("/user/queue/calls");
        BlockingQueue<String> laptopCalls = laptop.subscribe("/user/queue/calls");
        BlockingQueue<String> otherCalls = other.subscribe("/user/queue/calls");
        settle();

        // what call-signaling-service publishes into the shared channel (Phase 7, WEBSOCKET_DESIGN.md section 9)
        String eventJson = "{\"type\":\"CALL_INCOMING\",\"conversationId\":null,\"data\":{\"id\":\"c-1\","
                + "\"type\":\"VIDEO\",\"status\":\"RINGING\",\"direction\":\"INCOMING\"}}";
        redis.convertAndSend("ws.events", json(Map.of("userIds", List.of(callee.userId()),
                "destination", "/queue/calls", "json", eventJson)));

        for (BlockingQueue<String> queue : List.of(phoneCalls, laptopCalls)) {
            String received = next(queue);
            assertThat((String) JsonPath.read(received, "$.type")).isEqualTo("CALL_INCOMING");
            assertThat((String) JsonPath.read(received, "$.data.status")).isEqualTo("RINGING");
        }
        Thread.sleep(400);
        assertThat(otherCalls).as("other users get nothing").isEmpty();
    }

    @Test
    void senderIsToldWhenTheMessageIsDeliveredAndRead() throws Exception {
        Chat chat = newChat();
        UUID message = sendOk(chat.a(), chat.id(), "hello");
        TestStompClient alice = connect(chat.a());
        BlockingQueue<String> receipts = alice.subscribe("/user/queue/receipts");
        settle();

        markDelivered(chat.b(), message);
        String delivered = next(receipts);
        assertThat((String) JsonPath.read(delivered, "$.type")).isEqualTo("RECEIPT_UPDATED");
        assertThat((String) JsonPath.read(delivered, "$.data.status")).isEqualTo("DELIVERED");
        assertThat((String) JsonPath.read(delivered, "$.data.messageId")).isEqualTo(message.toString());
        assertThat((String) JsonPath.read(delivered, "$.data.userId")).isEqualTo(chat.b().userId().toString());

        markRead(chat.b(), chat.id(), message);
        String read = next(receipts);
        assertThat((String) JsonPath.read(read, "$.data.status")).isEqualTo("READ");
    }

    @Test
    void readReceiptsThatPrivacyHidesArriveAsDelivered() throws Exception {
        Chat chat = newChat();
        PEERS.setReadReceipts(chat.b().userId(), false);
        UUID message = sendOk(chat.a(), chat.id(), "hello");
        TestStompClient alice = connect(chat.a());
        BlockingQueue<String> receipts = alice.subscribe("/user/queue/receipts");
        settle();

        markRead(chat.b(), chat.id(), message);

        assertThat((String) JsonPath.read(next(receipts), "$.data.status")).isEqualTo("DELIVERED");
        PEERS.setReadReceipts(chat.b().userId(), true);
    }

    @Test
    void acknowledgementsCanAlsoBeSentOverTheSocket() throws Exception {
        Chat chat = newChat();
        UUID message = sendOk(chat.a(), chat.id(), "hello");
        TestStompClient alice = connect(chat.a());
        BlockingQueue<String> receipts = alice.subscribe("/user/queue/receipts");
        TestStompClient bob = connect(chat.b());
        settle();

        bob.send("/app/chat.delivered", json(Map.of("messageIds", List.of(message))));
        assertThat((String) JsonPath.read(next(receipts), "$.data.status")).isEqualTo("DELIVERED");

        bob.send("/app/chat.read", json(Map.of("conversationId", chat.id(), "upToMessageId", message)));
        assertThat((String) JsonPath.read(next(receipts), "$.data.status")).isEqualTo("READ");
    }

    // ------------------------------------------------------------------- typing

    @Test
    void typingReachesTheOtherParticipantOnly() throws Exception {
        Chat chat = newChat();
        sendOk(chat.a(), chat.id(), "so the members are known");
        TestStompClient alice = connect(chat.a());
        TestStompClient bob = connect(chat.b());
        TestStompClient carol = connect(Device.newUser());
        BlockingQueue<String> bobTyping = bob.subscribe("/user/queue/typing");
        BlockingQueue<String> carolTyping = carol.subscribe("/user/queue/typing");
        settle();

        alice.send("/app/chat.typing", json(Map.of("conversationId", chat.id(), "typing", true)));

        String event = next(bobTyping);
        assertThat((String) JsonPath.read(event, "$.type")).isEqualTo("TYPING");
        assertThat((String) JsonPath.read(event, "$.conversationId")).isEqualTo(chat.id().toString());
        assertThat((String) JsonPath.read(event, "$.data.userId")).isEqualTo(chat.a().userId().toString());
        assertThat((Boolean) JsonPath.read(event, "$.data.typing")).isTrue();
        Thread.sleep(500);
        assertThat(carolTyping).isEmpty();
    }

    @Test
    void typingInAConversationOurCopyDoesNotKnowYetAsksConversationService() throws Exception {
        Chat chat = newChat();          // no message yet: our membership copy knows nothing about it
        TestStompClient alice = connect(chat.a());
        TestStompClient bob = connect(chat.b());
        BlockingQueue<String> bobTyping = bob.subscribe("/user/queue/typing");
        settle();

        alice.send("/app/chat.typing", json(Map.of("conversationId", chat.id(), "typing", true)));

        assertThat((String) JsonPath.read(next(bobTyping), "$.data.userId")).isEqualTo(chat.a().userId().toString());
    }

    @Test
    void groupTypingReachesCurrentMembersButNotSomeoneWhoWasRemoved() throws Exception {
        Group group = newGroup(2);
        Device bob = group.member(0);
        Device carol = group.member(1);
        PEERS.removeMember(group.id(), carol.userId());
        sendOk(group.admin(), group.id(), "Carol is gone");      // our copy now knows that too
        TestStompClient admin = connect(group.admin());
        BlockingQueue<String> bobTyping = connect(bob).subscribe("/user/queue/typing");
        TestStompClient carolClient = connect(carol);
        BlockingQueue<String> carolTyping = carolClient.subscribe("/user/queue/typing");
        BlockingQueue<String> carolErrors = carolClient.subscribe("/user/queue/errors");
        settle();

        admin.send("/app/chat.typing", json(Map.of("conversationId", group.id(), "typing", true)));
        assertThat((String) JsonPath.read(next(bobTyping), "$.data.userId")).isEqualTo(group.admin().userId().toString());

        // and Carol may not type there any more
        carolClient.send("/app/chat.typing", json(Map.of("conversationId", group.id(), "typing", true)));
        assertThat((String) JsonPath.read(next(carolErrors), "$.data.code")).isEqualTo("NOT_FOUND");
        Thread.sleep(500);
        assertThat(carolTyping).isEmpty();
    }

    @Test
    void typingInAForeignConversationGivesAnErrorAndSendsNothing() throws Exception {
        Chat chat = newChat();
        TestStompClient bob = connect(chat.b());
        BlockingQueue<String> bobTyping = bob.subscribe("/user/queue/typing");
        TestStompClient stranger = connect(Device.newUser());
        BlockingQueue<String> errors = stranger.subscribe("/user/queue/errors");
        settle();

        stranger.send("/app/chat.typing", json(Map.of("conversationId", chat.id(), "typing", true)));

        String error = next(errors);
        assertThat((String) JsonPath.read(error, "$.type")).isEqualTo("ERROR");
        assertThat((String) JsonPath.read(error, "$.data.code")).isEqualTo("NOT_FOUND");
        Thread.sleep(500);
        assertThat(bobTyping).isEmpty();
    }

    // --------------------------------------------------------- sending by socket

    @Test
    void sendingOverTheSocketAcknowledgesAndDelivers() throws Exception {
        Chat chat = newChat();
        TestStompClient alice = connect(chat.a());
        TestStompClient bob = connect(chat.b());
        BlockingQueue<String> acks = alice.subscribe("/user/queue/acks");
        BlockingQueue<String> bobMessages = bob.subscribe("/user/queue/messages");
        settle();
        String key = newKey();

        alice.send("/app/chat.send", json(Map.of("conversationId", chat.id(), "type", "TEXT",
                "content", "over the socket", "idempotencyKey", key)));

        String ack = next(acks);
        assertThat((String) JsonPath.read(ack, "$.type")).isEqualTo("MESSAGE_ACK");
        assertThat((String) JsonPath.read(ack, "$.data.idempotencyKey")).isEqualTo(key);
        assertThat((String) JsonPath.read(ack, "$.data.message.status")).isEqualTo("SENT");
        assertThat((String) JsonPath.read(next(bobMessages), "$.data.content")).isEqualTo("over the socket");

        // a duplicate send with the same key is acknowledged with the same message and saved once
        alice.send("/app/chat.send", json(Map.of("conversationId", chat.id(), "type", "TEXT",
                "content", "over the socket", "idempotencyKey", key)));
        String again = next(acks);
        assertThat((String) JsonPath.read(again, "$.data.message.id")).isEqualTo((String) JsonPath.read(ack, "$.data.message.id"));
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE sender_id = ? AND idempotency_key = ?",
                Integer.class, chat.a().userId().toString(), key);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void rejectedSendsReturnAnErrorEventWithTheKey() throws Exception {
        Chat chat = newChat();
        TestStompClient alice = connect(chat.a());
        BlockingQueue<String> errors = alice.subscribe("/user/queue/errors");
        settle();

        // blocked
        PEERS.block(chat.b().userId(), chat.a().userId());
        String blockedKey = newKey();
        alice.send("/app/chat.send", json(Map.of("conversationId", chat.id(), "type", "TEXT",
                "content", "hi", "idempotencyKey", blockedKey)));
        String blocked = next(errors);
        assertThat((String) JsonPath.read(blocked, "$.data.code")).isEqualTo("MESSAGING_NOT_ALLOWED");
        assertThat((String) JsonPath.read(blocked, "$.data.idempotencyKey")).isEqualTo(blockedKey);
        PEERS.unblockAll();

        // invalid body
        alice.send("/app/chat.send", json(Map.of("conversationId", chat.id(), "type", "TEXT",
                "content", "", "idempotencyKey", newKey())));
        assertThat((String) JsonPath.read(next(errors), "$.data.code")).isEqualTo("VALIDATION");

        // not a participant
        TestStompClient stranger = connect(Device.newUser());
        BlockingQueue<String> strangerErrors = stranger.subscribe("/user/queue/errors");
        settle();
        stranger.send("/app/chat.send", json(Map.of("conversationId", chat.id(), "type", "TEXT",
                "content", "hi", "idempotencyKey", newKey())));
        assertThat((String) JsonPath.read(next(strangerErrors), "$.data.code")).isEqualTo("NOT_FOUND");

        // the connection survives these errors
        assertThat(alice.isConnected()).isTrue();
    }

    // ------------------------------------------------------- token / session expiry

    @Test
    void socketsWithAnExpiredTokenAreClosedByTheChecker() throws Exception {
        Device device = Device.newUser();
        String shortLived = TestJwt.create(device.userId(), device.sessionId(), Instant.now().plusSeconds(2));
        TestStompClient client = TestStompClient.connect(port, shortLived);
        clients.add(client);
        assertThat(client.isConnected()).isTrue();

        Thread.sleep(2_500);
        expiryChecker.checkSessions();

        assertThat(client.waitUntilClosed(WAIT_MS)).isTrue();
    }

    @Test
    void socketsOfAnEndedSessionAreClosedByTheChecker() throws Exception {
        Device device = Device.newUser();
        TestStompClient client = connect(device);
        TestStompClient other = connect(Device.newUser());
        assertThat(client.isConnected()).isTrue();

        redis.opsForValue().set("auth:revoked:" + device.sessionId(), "1", Duration.ofMinutes(5));
        expiryChecker.checkSessions();

        assertThat(client.waitUntilClosed(WAIT_MS)).isTrue();
        assertThat(other.isConnected()).as("other users stay connected").isTrue();
    }

    @Test
    void healthyConnectionsSurviveTheChecker() throws Exception {
        TestStompClient client = connect(Device.newUser());
        expiryChecker.checkSessions();
        Thread.sleep(300);
        assertThat(client.isConnected()).isTrue();
    }

    @Test
    void restCallsStillNeedATokenWhileTheHandshakeEndpointIsOpen() throws Exception {
        MvcResult result = get("/api/messages/summaries", null);
        assertStatus(result, 401);
    }
}
