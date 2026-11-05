package com.chatapp.conversation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import com.chatapp.conversation.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;

/**
 * Every group change reaches Kafka (topic conversation.events, key = conversation id) through the outbox,
 * in the order it happened and with exactly the payload fields of KAFKA_EVENT_DESIGN.md.
 */
class GroupEventsTest extends AbstractIntegrationTest {

    @Test
    void groupChangesArePublishedInOrderWithTheDocumentedPayloads() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Events");
        UUID bob = USER_SERVICE.addUser("Bob Events");
        UUID carol = USER_SERVICE.addUser("Carol Events");
        UUID dave = USER_SERVICE.addUser("Dave Events");
        String id = groupId(alice, "Events", bob);
        String base = "/api/conversations/" + id;

        assertStatus(postAs(alice, base + "/members", Map.of("userIds", List.of(carol.toString(), dave.toString()))), 200);
        assertStatus(deleteAs(alice, base + "/members/" + dave), 200);
        assertStatus(putAs(alice, base + "/members/" + carol + "/role", Map.of("role", "ADMIN")), 200);
        assertStatus(patchAs(alice, base + "/group", Map.of("name", "Renamed", "onlyAdminsCanSend", true)), 200);
        assertStatus(putAs(alice, base + "/members/" + carol + "/role", Map.of("role", "MEMBER")), 200);
        assertStatus(postAs(alice, base + "/leave", null), 200);   // last admin leaves: Bob (earliest) becomes admin

        List<String> events = eventsFor(id, 8);

        assertThat(events).extracting(json -> (String) JsonPath.read(json, "$.eventType")).containsExactly(
                "ConversationCreatedEvent", "GroupMembersAddedEvent", "GroupMemberRemovedEvent", "GroupRoleChangedEvent",
                "GroupUpdatedEvent", "GroupRoleChangedEvent", "GroupMemberRemovedEvent", "GroupRoleChangedEvent");
        events.forEach(json -> {
            assertThat((String) JsonPath.read(json, "$.aggregateId")).isEqualTo(id);
            assertThat((Integer) JsonPath.read(json, "$.schemaVersion")).isEqualTo(1);
        });

        String created = events.get(0);
        assertThat((String) JsonPath.read(created, "$.payload.type")).isEqualTo("GROUP");
        assertThat((String) JsonPath.read(created, "$.payload.createdBy")).isEqualTo(alice.toString());
        assertThat((String) JsonPath.read(created, "$.payload.groupName")).isEqualTo("Events");
        assertThat((String) JsonPath.read(created, "$.payload.createdByName")).isEqualTo("Alice Events");
        assertThat((List<String>) JsonPath.read(created, "$.payload.participantIds"))
                .containsExactly(alice.toString(), bob.toString());
        assertThat(micros(created, "$.payload.createdAt")).isTrue();

        String added = events.get(1);
        assertThat((String) JsonPath.read(added, "$.payload.conversationId")).isEqualTo(id);
        assertThat((String) JsonPath.read(added, "$.payload.actorId")).isEqualTo(alice.toString());
        assertThat((String) JsonPath.read(added, "$.payload.actorName")).isEqualTo("Alice Events");
        assertThat((List<String>) JsonPath.read(added, "$.payload.members[*].userId"))
                .containsExactly(carol.toString(), dave.toString());
        assertThat((List<String>) JsonPath.read(added, "$.payload.members[*].name"))
                .containsExactly("Carol Events", "Dave Events");
        assertThat(micros(added, "$.payload.joinedAt")).isTrue();

        String removed = events.get(2);
        assertThat((String) JsonPath.read(removed, "$.payload.userId")).isEqualTo(dave.toString());
        assertThat((String) JsonPath.read(removed, "$.payload.userName")).isEqualTo("Dave Events");
        assertThat((String) JsonPath.read(removed, "$.payload.reason")).isEqualTo("REMOVED");
        assertThat((String) JsonPath.read(removed, "$.payload.actorId")).isEqualTo(alice.toString());
        assertThat(micros(removed, "$.payload.leftAt")).isTrue();

        String promoted = events.get(3);
        assertThat((String) JsonPath.read(promoted, "$.payload.userId")).isEqualTo(carol.toString());
        assertThat((String) JsonPath.read(promoted, "$.payload.role")).isEqualTo("ADMIN");
        assertThat((String) JsonPath.read(promoted, "$.payload.at")).isNotNull();

        String updated = events.get(4);
        assertThat((String) JsonPath.read(updated, "$.payload.name")).isEqualTo("Renamed");
        assertThat((Boolean) JsonPath.read(updated, "$.payload.descriptionChanged")).isFalse();
        assertThat((Boolean) JsonPath.read(updated, "$.payload.onlyAdminsCanSend")).isTrue();
        assertThat((Object) JsonPath.read(updated, "$.payload.onlyAdminsCanEdit")).isNull();

        String left = events.get(6);
        assertThat((String) JsonPath.read(left, "$.payload.reason")).isEqualTo("LEFT");
        assertThat((String) JsonPath.read(left, "$.payload.userId")).isEqualTo(alice.toString());
        assertThat((String) JsonPath.read(left, "$.payload.actorId")).isEqualTo(alice.toString());

        String automatic = events.get(7);
        assertThat((Object) JsonPath.read(automatic, "$.payload.actorId")).isNull();
        assertThat((Object) JsonPath.read(automatic, "$.payload.actorName")).isNull();
        assertThat((String) JsonPath.read(automatic, "$.payload.userId")).isEqualTo(bob.toString());
        assertThat((String) JsonPath.read(automatic, "$.payload.userName")).isEqualTo("Bob Events");
        assertThat((String) JsonPath.read(automatic, "$.payload.role")).isEqualTo("ADMIN");
    }

    @Test
    void changesThatChangeNothingPublishNothing() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Events");
        UUID bob = USER_SERVICE.addUser("Bob Events");
        String id = groupId(alice, "Quiet", bob);
        String base = "/api/conversations/" + id;

        assertStatus(patchAs(alice, base + "/group", Map.of("name", "Quiet", "onlyAdminsCanSend", false)), 200);
        assertStatus(putAs(alice, base + "/members/" + bob + "/role", Map.of("role", "MEMBER")), 200);
        assertStatus(putAs(alice, base + "/pin", null), 200);   // a personal setting is never an event

        assertThat(countRows("outbox_events", "aggregate_id", id)).isEqualTo(1);   // only "created"
    }

    // ------------------------------------------------------------------ helpers

    /** Reads conversation.events from the start until `count` events with this key arrived. */
    private List<String> eventsFor(String conversationId, int count) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        List<String> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("conversation.events"));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> {
                    if (conversationId.equals(record.key())) {
                        found.add(record.value());
                    }
                });
                return found.size() >= count;
            });
        }
        return found;
    }

    /** True when the time has at most microsecond precision (the database precision). */
    private static boolean micros(String json, String path) {
        Instant time = Instant.parse(JsonPath.read(json, path));
        return time.getNano() % 1_000 == 0;
    }
}
