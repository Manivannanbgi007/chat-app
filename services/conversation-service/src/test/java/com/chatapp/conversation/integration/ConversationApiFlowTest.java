package com.chatapp.conversation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.chatapp.conversation.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** The whole service on a real MySQL and Kafka, talking to a fake user-service. */
class ConversationApiFlowTest extends AbstractIntegrationTest {

    @AfterEach
    void userServiceIsHealthyAgain() {
        USER_SERVICE.setFailing(false);
    }

    // ------------------------------------------------------------ create / read

    @Test
    void creatingAConversationReturns201AndItAppearsInBothChatLists() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Test");
        UUID bob = USER_SERVICE.addUser("Bob Test");

        MvcResult created = startConversation(alice, bob);

        assertStatus(created, 201);
        String id = field(created, "$.data.id");
        assertThat(field(created, "$.data.type")).isEqualTo("PRIVATE");
        assertThat(field(created, "$.data.lastMessageAt")).isNull();
        assertThat(field(created, "$.data.otherUser.displayName")).isEqualTo("Bob Test");
        assertThat(field(created, "$.data.otherUser.id")).isEqualTo(bob.toString());
        List<String> participantIds = JsonPath.read(created.getResponse().getContentAsString(), "$.data.participants[*].id");
        assertThat(participantIds).containsExactlyInAnyOrder(alice.toString(), bob.toString());

        // each side sees the OTHER person as otherUser
        MvcResult aliceList = getAs(alice, "/api/conversations");
        assertStatus(aliceList, 200);
        assertThat(field(aliceList, "$.data[0].id")).isEqualTo(id);
        assertThat(field(aliceList, "$.data[0].otherUser.displayName")).isEqualTo("Bob Test");

        MvcResult bobList = getAs(bob, "/api/conversations");
        assertThat(field(bobList, "$.data[0].id")).isEqualTo(id);
        assertThat(field(bobList, "$.data[0].otherUser.displayName")).isEqualTo("Alice Test");

        // the data really is in the database: 2 participants and the pair key
        assertThat(countRows("conversation_participants", "conversation_id", id)).isEqualTo(2);
        assertThat(countRows("private_conversation_keys", "conversation_id", id)).isEqualTo(1);
    }

    @Test
    void theSamePairGetsTheSameConversationFromEitherSide() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Test");
        UUID bob = USER_SERVICE.addUser("Bob Test");

        MvcResult first = startConversation(alice, bob);
        MvcResult again = startConversation(alice, bob);
        MvcResult fromBobsSide = startConversation(bob, alice);

        assertStatus(first, 201);
        assertStatus(again, 200);
        assertStatus(fromBobsSide, 200);
        assertThat(field(again, "$.data.id")).isEqualTo(field(first, "$.data.id"));
        assertThat(field(fromBobsSide, "$.data.id")).isEqualTo(field(first, "$.data.id"));
        assertThat(getAs(alice, "/api/conversations").getResponse().getContentAsString()).contains(field(first, "$.data.id"));
    }

    @Test
    void manyRequestsAtTheSameTimeCreateExactlyOneConversation() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Test");
        UUID bob = USER_SERVICE.addUser("Bob Test");

        int requests = 8;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            // half of the requests come from Alice, half from Bob
            UUID from = i % 2 == 0 ? alice : bob;
            UUID to = i % 2 == 0 ? bob : alice;
            Callable<MvcResult> call = () -> {
                go.await();
                return startConversation(from, to);
            };
            futures.add(pool.submit(call));
        }
        go.countDown();

        List<MvcResult> results = new ArrayList<>();
        for (Future<MvcResult> future : futures) {
            results.add(future.get());
        }
        pool.shutdown();

        long created = results.stream().filter(r -> r.getResponse().getStatus() == 201).count();
        long found = results.stream().filter(r -> r.getResponse().getStatus() == 200).count();
        assertThat(created).as("exactly one request creates it").isEqualTo(1);
        assertThat(found).isEqualTo(requests - 1);
        assertThat(results.stream().map(r -> {
            try {
                return field(r, "$.data.id");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).distinct()).hasSize(1);

        Integer pairRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM private_conversation_keys WHERE user_low_id IN (?, ?) AND user_high_id IN (?, ?)",
                Integer.class, alice.toString(), bob.toString(), alice.toString(), bob.toString());
        assertThat(pairRows).isEqualTo(1);
    }

    @Test
    void getReturnsTheConversationToParticipantsOnlyAndWithTheSame404ForUnknownIds() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Test");
        UUID bob = USER_SERVICE.addUser("Bob Test");
        UUID carol = USER_SERVICE.addUser("Carol Test");
        String id = field(startConversation(alice, bob), "$.data.id");

        MvcResult asBob = getAs(bob, "/api/conversations/" + id);
        assertStatus(asBob, 200);
        assertThat(field(asBob, "$.data.otherUser.displayName")).isEqualTo("Alice Test");

        MvcResult asStranger = getAs(carol, "/api/conversations/" + id);
        MvcResult unknown = getAs(carol, "/api/conversations/" + UUID.randomUUID());
        assertStatus(asStranger, 404);
        assertStatus(unknown, 404);
        assertThat(field(asStranger, "$.message")).isEqualTo(field(unknown, "$.message"));

        // and the stranger does not see it in the list either
        assertThat(getAs(carol, "/api/conversations").getResponse().getContentAsString()).doesNotContain(id);
    }

    @Test
    void theChatListIsSortedByLastActivityNewestFirst() throws Exception {
        UUID me = USER_SERVICE.addUser("Me Test");
        UUID bob = USER_SERVICE.addUser("Bob Test");
        UUID carol = USER_SERVICE.addUser("Carol Test");
        UUID dave = USER_SERVICE.addUser("Dave Test");

        String withBob = field(startConversation(me, bob), "$.data.id");
        String withCarol = field(startConversation(me, carol), "$.data.id");
        String withDave = field(startConversation(me, dave), "$.data.id");

        // Bob: old message. Carol: a message right now. Dave: no message (sorted by when it was created).
        setTimeFromNow("conversations", "last_message_at", UUID.fromString(withBob), -3600);
        setTimeFromNow("conversations", "last_message_at", UUID.fromString(withCarol), 60);

        MvcResult list = getAs(me, "/api/conversations");
        List<String> ids = JsonPath.read(list.getResponse().getContentAsString(), "$.data[*].id");
        assertThat(ids).containsExactly(withCarol, withDave, withBob);
        assertThat(field(list, "$.data[2].lastMessageAt")).isNotNull();
    }

    @Test
    void theWholeChatListNeedsOnlyOneUserServiceCall() throws Exception {
        UUID me = USER_SERVICE.addUser("Me Test");
        for (int i = 0; i < 3; i++) {
            startConversation(me, USER_SERVICE.addUser("Friend " + i));
        }

        int before = USER_SERVICE.summaryCalls();
        assertStatus(getAs(me, "/api/conversations"), 200);

        assertThat(USER_SERVICE.summaryCalls() - before).isEqualTo(1);
        assertThat(USER_SERVICE.receivedTokens()).allMatch("test-internal-token-test-internal-token"::equals);
    }

    @Test
    void aParticipantWhoseProfileCannotBeLoadedIsShownAsUnknownUser() throws Exception {
        UUID me = USER_SERVICE.addUser("Me Test");
        UUID bob = USER_SERVICE.addUser("Bob Test");
        String id = field(startConversation(me, bob), "$.data.id");

        // Pretend user-service forgot Bob: a fresh fake that does not know him is simulated by a made-up id row
        UUID ghost = UUID.randomUUID();
        jdbc.update("UPDATE conversation_participants SET user_id = ? WHERE conversation_id = ? AND user_id = ?",
                ghost.toString(), id, bob.toString());

        MvcResult result = getAs(me, "/api/conversations/" + id);
        assertStatus(result, 200);
        assertThat(field(result, "$.data.otherUser.displayName")).isEqualTo("Unknown user");
        assertThat(field(result, "$.data.otherUser.id")).isEqualTo(ghost.toString());
    }

    // -------------------------------------------------------------------- rules

    @Test
    void cannotTalkToYourselfAndAGroupNeedsANameAndMembers() throws Exception {
        UUID me = USER_SERVICE.addUser("Me Test");

        assertStatus(startConversation(me, me), 400);

        // a GROUP request with only a userId (the private-chat field) is missing the group fields
        MvcResult group = mockMvc.perform(post("/api/conversations").header("Authorization", bearer(me))
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("type", "GROUP", "userId", UUID.randomUUID().toString())))).andReturn();
        assertStatus(group, 400);
        assertThat(field(group, "$.message")).isEqualTo("Validation failed");
        assertThat(field(group, "$.data.name")).isNotNull();
    }

    @Test
    void unknownUserIs404() throws Exception {
        UUID me = USER_SERVICE.addUser("Me Test");

        assertStatus(startConversation(me, UUID.randomUUID()), 404);
    }

    @Test
    void blockingInEitherDirectionStopsANewConversation() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Test");
        UUID bob = USER_SERVICE.addUser("Bob Test");
        UUID carol = USER_SERVICE.addUser("Carol Test");
        USER_SERVICE.block(alice, bob);     // Alice blocked Bob
        USER_SERVICE.block(carol, alice);   // Carol blocked Alice

        MvcResult aliceToBob = startConversation(alice, bob);
        MvcResult bobToAlice = startConversation(bob, alice);
        MvcResult aliceToCarol = startConversation(alice, carol);

        for (MvcResult result : List.of(aliceToBob, bobToAlice, aliceToCarol)) {
            assertStatus(result, 403);
            assertThat(field(result, "$.data.errorCode")).isEqualTo("MESSAGING_NOT_ALLOWED");
        }
        assertThat(getAs(alice, "/api/conversations").getResponse().getContentAsString()).doesNotContain(bob.toString());
    }

    @Test
    void whenUserServiceIsDownTheAnswerIs503AndNothingIsSaved() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Test");
        UUID bob = USER_SERVICE.addUser("Bob Test");
        int conversationsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM conversations", Integer.class);

        USER_SERVICE.setFailing(true);
        MvcResult down = startConversation(alice, bob);

        assertStatus(down, 503);
        assertThat(field(down, "$.data.errorCode")).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversations", Integer.class)).isEqualTo(conversationsBefore);

        // and it works again as soon as user-service is back
        USER_SERVICE.setFailing(false);
        assertStatus(startConversation(alice, bob), 201);
    }

    @Test
    void theApiNeedsAUserToken() throws Exception {
        assertStatus(mockMvc.perform(get("/api/conversations")).andReturn(), 401);
        assertStatus(mockMvc.perform(get("/actuator/health")).andReturn(), 200);
    }

    // -------------------------------------------------------------------- Kafka

    @Test
    void creatingAConversationQueuesAnEventThatReachesKafkaKeyedByConversationId() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Test");
        UUID bob = USER_SERVICE.addUser("Bob Test");
        String id = field(startConversation(alice, bob), "$.data.id");

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("conversation.events"));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> {
                    if (id.equals(record.key())) {
                        found.add(record);
                    }
                });
                return !found.isEmpty();
            });
        }

        String json = found.get(0).value();
        assertThat((String) JsonPath.read(json, "$.eventType")).isEqualTo("ConversationCreatedEvent");
        assertThat((Integer) JsonPath.read(json, "$.schemaVersion")).isEqualTo(1);
        assertThat((String) JsonPath.read(json, "$.aggregateId")).isEqualTo(id);
        assertThat((String) JsonPath.read(json, "$.payload.conversationId")).isEqualTo(id);
        assertThat((String) JsonPath.read(json, "$.payload.type")).isEqualTo("PRIVATE");
        List<String> participants = JsonPath.read(json, "$.payload.participantIds");
        assertThat(participants).containsExactlyInAnyOrder(alice.toString(), bob.toString());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer published = jdbc.queryForObject(
                    "SELECT published FROM outbox_events WHERE aggregate_id = ?", Integer.class, id);
            assertThat(published).isEqualTo(1);
        });
    }
}
