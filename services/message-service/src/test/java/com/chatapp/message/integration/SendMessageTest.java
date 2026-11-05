package com.chatapp.message.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.chatapp.message.support.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class SendMessageTest extends AbstractIntegrationTest {

    @AfterEach
    void resetFakes() {
        PEERS.unblockAll();
        PEERS.conversationServiceDown = false;
        PEERS.userServiceDown = false;
    }

    @Test
    void sendSavesTheMessageAndAnswers201WithStatusSent() throws Exception {
        Chat chat = newChat();

        MvcResult result = send(chat.a(), chat.id(), "Hello Bob", newKey());

        assertStatus(result, 201);
        assertThat(field(result, "$.data.content")).isEqualTo("Hello Bob");
        assertThat(field(result, "$.data.senderId")).isEqualTo(chat.a().userId().toString());
        assertThat(field(result, "$.data.status")).isEqualTo("SENT");
        assertThat(field(result, "$.data.myReceipt")).isNull();
        // saved before the answer was given
        Integer saved = jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE id = ?", Integer.class, field(result, "$.data.id"));
        assertThat(saved).isEqualTo(1);
    }

    @Test
    void timestampIsSetByTheServer() throws Exception {
        Chat chat = newChat();
        Instant before = Instant.now().minusSeconds(2);

        MvcResult result = send(chat.a(), chat.id(), "time check", newKey());

        Instant createdAt = Instant.parse(field(result, "$.data.createdAt"));
        assertThat(createdAt).isBetween(before, Instant.now().plusSeconds(2));
    }

    @Test
    void senderIsTakenFromTheTokenNotFromTheRequest() throws Exception {
        Chat chat = newChat();
        // an extra, forged sender field must be ignored
        MvcResult result = post("/api/messages", Map.of("conversationId", chat.id(), "type", "TEXT", "content", "hi",
                "idempotencyKey", newKey(), "senderId", chat.b().userId()), chat.a());

        assertStatus(result, 201);
        assertThat(field(result, "$.data.senderId")).isEqualTo(chat.a().userId().toString());
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameMessageAndSavesOnlyOnce() throws Exception {
        Chat chat = newChat();
        String key = newKey();

        MvcResult first = send(chat.a(), chat.id(), "once", key);
        MvcResult retry = send(chat.a(), chat.id(), "once", key);

        assertStatus(first, 201);
        assertStatus(retry, 200);
        assertThat(field(retry, "$.data.id")).isEqualTo(field(first, "$.data.id"));
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE sender_id = ? AND idempotency_key = ?",
                Integer.class, chat.a().userId().toString(), key);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void parallelRequestsWithTheSameKeyCreateExactlyOneMessage() throws Exception {
        Chat chat = newChat();
        String key = newKey();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<CompletableFuture<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return send(chat.a(), chat.id(), "race", key);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, pool));
            }
            List<String> ids = new ArrayList<>();
            for (CompletableFuture<MvcResult> call : calls) {
                MvcResult result = call.get();
                assertThat(result.getResponse().getStatus()).isIn(200, 201);
                ids.add(field(result, "$.data.id"));
            }
            assertThat(ids).allMatch(id -> id.equals(ids.get(0)));
        } finally {
            pool.shutdown();
        }
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE sender_id = ? AND idempotency_key = ?",
                Integer.class, chat.a().userId().toString(), key);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void sameKeyFromAnotherUserIsADifferentMessage() throws Exception {
        Chat chat = newChat();
        String key = newKey();

        assertStatus(send(chat.a(), chat.id(), "from a", key), 201);
        assertStatus(send(chat.b(), chat.id(), "from b", key), 201);
    }

    @Test
    void nonParticipantGets404AndNothingIsSaved() throws Exception {
        Chat chat = newChat();
        Device stranger = Device.newUser();
        String key = newKey();

        MvcResult result = send(stranger, chat.id(), "let me in", key);

        assertStatus(result, 404);
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE idempotency_key = ?", Integer.class, key);
        assertThat(rows).isZero();
    }

    @Test
    void unknownConversationGetsTheSame404() throws Exception {
        MvcResult result = send(Device.newUser(), UUID.randomUUID(), "hello?", newKey());
        assertStatus(result, 404);
        assertThat(field(result, "$.message")).isEqualTo("Conversation not found");
    }

    @Test
    void blockedPairCannotMessageInEitherDirection() throws Exception {
        Chat chat = newChat();
        PEERS.block(chat.b().userId(), chat.a().userId());      // Bob blocked Alice

        MvcResult fromBlocked = send(chat.a(), chat.id(), "hello?", newKey());
        MvcResult fromBlocker = send(chat.b(), chat.id(), "hello?", newKey());

        assertStatus(fromBlocked, 403);
        assertStatus(fromBlocker, 403);
        assertThat(field(fromBlocked, "$.data.errorCode")).isEqualTo("MESSAGING_NOT_ALLOWED");
        // same answer for both: the blocked user learns nothing about who blocked whom
        assertThat(field(fromBlocker, "$.message")).isEqualTo(field(fromBlocked, "$.message"));
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        Chat chat = newChat();

        MvcResult blank = send(chat.a(), chat.id(), "   ", newKey());
        assertStatus(blank, 400);

        MvcResult tooLong = send(chat.a(), chat.id(), "x".repeat(4001), newKey());
        assertStatus(tooLong, 400);
        assertThat(field(tooLong, "$.data.content")).contains("4000");

        MvcResult shortKey = send(chat.a(), chat.id(), "hi", "short");
        assertStatus(shortKey, 400);

        MvcResult image = post("/api/messages", Map.of("conversationId", chat.id(), "type", "LOCATION", "content", "x",
                "idempotencyKey", newKey()), chat.a());
        assertStatus(image, 400);
        assertThat(field(image, "$.data.errorCode")).isEqualTo("UNSUPPORTED_TYPE");

        MvcResult noConversation = post("/api/messages", Map.of("type", "TEXT", "content", "x", "idempotencyKey", newKey()), chat.a());
        assertStatus(noConversation, 400);

        assertThat(send(chat.a(), chat.id(), "x".repeat(4000), newKey()).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void requestWithoutTokenIs401() throws Exception {
        Chat chat = newChat();
        MvcResult result = post("/api/messages", Map.of("conversationId", chat.id(), "type", "TEXT", "content", "x",
                "idempotencyKey", newKey()), null);
        assertStatus(result, 401);
    }

    @Test
    void conversationServiceDownGives503AndSavesNothing() throws Exception {
        Chat chat = newChat();
        PEERS.conversationServiceDown = true;
        String key = newKey();

        MvcResult result = send(chat.a(), chat.id(), "are you there?", key);

        assertStatus(result, 503);
        assertThat(field(result, "$.data.errorCode")).isEqualTo("DEPENDENCY_UNAVAILABLE");
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE idempotency_key = ?", Integer.class, key);
        assertThat(rows).isZero();
    }

    @Test
    void userServiceDownGives503() throws Exception {
        Chat chat = newChat();
        PEERS.userServiceDown = true;
        assertStatus(send(chat.a(), chat.id(), "block check impossible", newKey()), 503);
    }

    @Test
    void retryOfAnAlreadySavedMessageWorksEvenIfOtherServicesAreDown() throws Exception {
        Chat chat = newChat();
        String key = newKey();
        assertStatus(send(chat.a(), chat.id(), "saved", key), 201);
        PEERS.conversationServiceDown = true;
        PEERS.userServiceDown = true;

        assertStatus(send(chat.a(), chat.id(), "saved", key), 200);
    }

    @Test
    void concurrentSendersKeepAConsistentOrderAndLoseNothing() throws Exception {
        Chat chat = newChat();
        int perSender = 15;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<CompletableFuture<Void>> jobs = new ArrayList<>();
            for (Device sender : List.of(chat.a(), chat.b())) {
                for (int i = 0; i < perSender; i++) {
                    int n = i;
                    jobs.add(CompletableFuture.runAsync(() -> {
                        try {
                            assertStatus(send(sender, chat.id(), "m" + n, newKey()), 201);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }, pool));
                }
            }
            CompletableFuture.allOf(jobs.toArray(new CompletableFuture[0])).get();
        } finally {
            pool.shutdown();
        }

        // history (newest first) must be exactly sorted by (created_at, id) and complete
        List<String> ids = new ArrayList<>();
        String cursor = null;
        do {
            MvcResult page = get("/api/conversations/" + chat.id() + "/messages?limit=10"
                    + (cursor == null ? "" : "&before=" + cursor), chat.a());
            assertStatus(page, 200);
            List<String> pageIds = read(page, "$.data.items[*].id");
            ids.addAll(pageIds);
            cursor = Boolean.TRUE.equals(read(page, "$.data.hasMore")) ? field(page, "$.data.nextCursor") : null;
        } while (cursor != null);
        assertThat(ids).hasSize(perSender * 2).doesNotHaveDuplicates();

        List<String> sortedByDb = jdbc.queryForList(
                "SELECT id FROM messages WHERE conversation_id = ? ORDER BY created_at DESC, id DESC",
                String.class, chat.id().toString());
        assertThat(ids).containsExactlyElementsOf(sortedByDb);
    }

    @Test
    void sendingQueuesAKafkaEventWithoutTheMessageText() throws Exception {
        Chat chat = newChat();
        String secretText = "top secret text " + UUID.randomUUID();
        UUID messageId = UUID.fromString(field(send(chat.a(), chat.id(), secretText, newKey()), "$.data.id"));

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        ConsumerRecord<String, String> found = null;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("message.events"));
            long deadline = System.currentTimeMillis() + 30_000;
            while (found == null && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value().contains(messageId.toString())) {
                        found = record;
                    }
                }
            }
        }

        assertThat(found).as("MessageCreatedEvent on message.events").isNotNull();
        assertThat(found.key()).isEqualTo(chat.id().toString());            // key = conversation id
        String json = found.value();
        assertThat((String) com.jayway.jsonpath.JsonPath.read(json, "$.eventType")).isEqualTo("MessageCreatedEvent");
        assertThat((String) com.jayway.jsonpath.JsonPath.read(json, "$.payload.senderId")).isEqualTo(chat.a().userId().toString());
        List<String> recipients = com.jayway.jsonpath.JsonPath.read(json, "$.payload.recipientIds");
        assertThat(recipients).containsExactly(chat.b().userId().toString());
        assertThat(json).doesNotContain(secretText);
    }

    @Test
    void sendingRemembersWhoIsInTheConversation() throws Exception {
        Chat chat = newChat();
        sendOk(chat.a(), chat.id(), "hello");

        List<String> members = jdbc.queryForList(
                "SELECT user_id FROM conversation_members WHERE conversation_id = ? AND left_at IS NULL",
                String.class, chat.id().toString());
        assertThat(members).containsExactlyInAnyOrder(chat.a().userId().toString(), chat.b().userId().toString());
    }
}
