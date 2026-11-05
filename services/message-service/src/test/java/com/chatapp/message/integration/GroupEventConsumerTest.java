package com.chatapp.message.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import com.chatapp.message.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * conversation.events -> our membership copy + SYSTEM messages (written by ConversationEventConsumer).
 * The events are put on the real Kafka exactly as conversation-service's outbox would.
 */
class GroupEventConsumerTest extends AbstractIntegrationTest {

    /** A group known to the fake conversation-service AND announced on Kafka, with the same creation time. */
    private record CreatedGroup(UUID id, Device admin, Device bob, Device carol, Instant createdAt, UUID eventId) {
    }

    private CreatedGroup createGroup() throws Exception {
        Device admin = Device.newUser();
        Device bob = Device.newUser();
        Device carol = Device.newUser();
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        PEERS.addGroupAt(id, createdAt, admin.userId(), bob.userId(), carol.userId());

        UUID eventId = publishEvent("ConversationCreatedEvent", id, Map.of(
                "type", "GROUP",
                "participantIds", List.of(admin.userId().toString(), bob.userId().toString(), carol.userId().toString()),
                "createdAt", createdAt.toString(),
                "createdBy", admin.userId().toString(),
                "groupName", "Trip to Goa",
                "createdByName", "Asha"));
        awaitSystemMessages(id, 1);
        return new CreatedGroup(id, admin, bob, carol, createdAt, eventId);
    }

    private void awaitSystemMessages(UUID conversationId, int count) throws Exception {
        await(count + " system message(s) in " + conversationId, () -> systemMessages(conversationId).size() >= count);
    }

    private List<Map<String, Object>> systemMessages(UUID conversationId) {
        return jdbc.queryForList("SELECT id, sender_id, content, created_at, idempotency_key FROM messages "
                + "WHERE conversation_id = ? AND type = 'SYSTEM' ORDER BY created_at, id", conversationId.toString());
    }

    private static String content(Map<String, Object> row) {
        return (String) row.get("content");
    }

    private List<Map<String, Object>> periods(UUID conversationId, UUID userId) {
        return jdbc.queryForList("SELECT joined_at, left_at, conversation_type FROM conversation_members "
                + "WHERE conversation_id = ? AND user_id = ? ORDER BY joined_at", conversationId.toString(), userId.toString());
    }

    private Map<String, Object> summaryOf(Device viewer, UUID conversationId) throws Exception {
        List<Map<String, Object>> rows = read(get("/api/messages/summaries", viewer),
                "$.data[?(@.conversationId=='" + conversationId + "')]");
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> user(Device device, String name) {
        Map<String, Object> user = new HashMap<>();
        user.put("userId", device.userId().toString());
        user.put("name", name);
        return user;
    }

    // ------------------------------------------------------------------ events

    @Test
    void aNewGroupGetsMembershipPeriodsAndACreatedMessage() throws Exception {
        CreatedGroup group = createGroup();

        Map<String, Object> created = systemMessages(group.id()).get(0);
        String json = content(created);
        assertThat((String) JsonPath.read(json, "$.event")).isEqualTo("GROUP_CREATED");
        assertThat((String) JsonPath.read(json, "$.actor.id")).isEqualTo(group.admin().userId().toString());
        assertThat((String) JsonPath.read(json, "$.actor.name")).isEqualTo("Asha");
        assertThat((String) JsonPath.read(json, "$.name")).isEqualTo("Trip to Goa");
        assertThat(created.get("sender_id")).isEqualTo(group.admin().userId().toString());
        assertThat(created.get("idempotency_key")).isEqualTo("sys-" + group.eventId() + "-1");

        for (Device member : List.of(group.admin(), group.bob(), group.carol())) {
            List<Map<String, Object>> rows = periods(group.id(), member.userId());
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("left_at")).isNull();
            assertThat(rows.get(0).get("conversation_type")).isEqualTo("GROUP");
        }

        // the outbox announces it like any other message (type SYSTEM, recipients = the other members)
        String payload = jdbc.queryForObject("SELECT payload FROM outbox_events WHERE event_type = 'MessageCreatedEvent' "
                + "AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.payload.messageId')) = ?", String.class, created.get("id"));
        assertThat((String) JsonPath.read(payload, "$.payload.type")).isEqualTo("SYSTEM");
        List<String> recipients = JsonPath.read(payload, "$.payload.recipientIds");
        assertThat(recipients).containsExactlyInAnyOrder(group.bob().userId().toString(), group.carol().userId().toString());
    }

    @Test
    void systemMessagesAppearInHistoryButAreNeverUnreadOrAcknowledged() throws Exception {
        CreatedGroup group = createGroup();
        String systemId = (String) systemMessages(group.id()).get(0).get("id");

        MvcResult history = get("/api/conversations/" + group.id() + "/messages", group.bob());
        assertStatus(history, 200);
        assertThat(field(history, "$.data.items[0].type")).isEqualTo("SYSTEM");
        assertThat(field(history, "$.data.items[0].status")).isNull();
        assertThat(field(history, "$.data.items[0].myReceipt")).isNull();

        Map<String, Object> summary = summaryOf(group.bob(), group.id());
        assertThat(summary).isNotNull();
        assertThat(((Number) summary.get("unreadCount")).intValue()).isZero();
        assertThat(((Map<?, ?>) summary.get("lastMessage")).get("type")).isEqualTo("SYSTEM");

        assertThat(field(markDelivered(group.bob(), UUID.fromString(systemId)), "$.data.updated")).isEqualTo("0");
        MvcResult read = markRead(group.bob(), group.id(), UUID.fromString(systemId));
        assertStatus(read, 200);
        assertThat(field(read, "$.data.updated")).isEqualTo("0");

        // the creator's own view: no status either
        MvcResult asAdmin = get("/api/conversations/" + group.id() + "/messages", group.admin());
        assertThat(field(asAdmin, "$.data.items[0].status")).isNull();
    }

    @Test
    void addedMembersGetAPeriodAndSeeOnlyTheMessagesFromTheirJoin() throws Exception {
        CreatedGroup group = createGroup();
        Device dave = Device.newUser();
        Instant joinedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        publishEvent("GroupMembersAddedEvent", group.id(), Map.of(
                "actorId", group.admin().userId().toString(), "actorName", "Asha",
                "members", List.of(user(dave, "Dave")), "joinedAt", joinedAt.toString()));
        awaitSystemMessages(group.id(), 2);

        String json = content(systemMessages(group.id()).get(1));
        assertThat((String) JsonPath.read(json, "$.event")).isEqualTo("MEMBERS_ADDED");
        assertThat((String) JsonPath.read(json, "$.actor.name")).isEqualTo("Asha");
        assertThat((String) JsonPath.read(json, "$.users[0].id")).isEqualTo(dave.userId().toString());
        assertThat((String) JsonPath.read(json, "$.users[0].name")).isEqualTo("Dave");
        assertThat(periods(group.id(), dave.userId())).hasSize(1);

        // Dave sees "added" but not the older "created" message
        Map<String, Object> summary = summaryOf(dave, group.id());
        String last = (String) ((Map<?, ?>) summary.get("lastMessage")).get("content");
        assertThat((String) JsonPath.read(last, "$.event")).isEqualTo("MEMBERS_ADDED");
        List<String> synced = read(get("/api/messages/sync?after=" + field(get("/api/messages/sync", dave), "$.data.nextCursor"), dave),
                "$.data.items[*].content");
        assertThat(synced.stream().map(c -> (String) JsonPath.read(c, "$.event"))).doesNotContain("GROUP_CREATED");
    }

    @Test
    void removedAndLeftMembersGetTheirPeriodClosedAndStillSeeWhyTheyLeft() throws Exception {
        CreatedGroup group = createGroup();
        Instant removedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        publishEvent("GroupMemberRemovedEvent", group.id(), Map.of(
                "actorId", group.admin().userId().toString(), "actorName", "Asha",
                "userId", group.carol().userId().toString(), "userName", "Carol",
                "reason", "REMOVED", "leftAt", removedAt.toString()));
        Thread.sleep(2);
        Instant leftAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        publishEvent("GroupMemberRemovedEvent", group.id(), Map.of(
                "actorId", group.bob().userId().toString(), "actorName", "Bob",
                "userId", group.bob().userId().toString(), "userName", "Bob",
                "reason", "LEFT", "leftAt", leftAt.toString()));
        awaitSystemMessages(group.id(), 3);

        List<Map<String, Object>> messages = systemMessages(group.id());
        String removed = content(messages.get(1));
        assertThat((String) JsonPath.read(removed, "$.event")).isEqualTo("MEMBER_REMOVED");
        assertThat((String) JsonPath.read(removed, "$.users[0].id")).isEqualTo(group.carol().userId().toString());
        assertThat((String) JsonPath.read(removed, "$.users[0].name")).isEqualTo("Carol");
        String left = content(messages.get(2));
        assertThat((String) JsonPath.read(left, "$.event")).isEqualTo("MEMBER_LEFT");
        assertThat((String) JsonPath.read(left, "$.actor.id")).isEqualTo(group.bob().userId().toString());
        assertThat(JsonPath.<Map<String, Object>>read(left, "$")).doesNotContainKey("users");
        assertThat(messages.get(2).get("sender_id")).isEqualTo(group.bob().userId().toString());

        assertThat(periods(group.id(), group.carol().userId()).get(0).get("left_at")).isNotNull();
        assertThat(periods(group.id(), group.bob().userId()).get(0).get("left_at")).isNotNull();
        assertThat(periods(group.id(), group.admin().userId()).get(0).get("left_at")).isNull();

        // Carol's last visible message is the one that tells her she was removed
        String carolLast = (String) ((Map<?, ?>) summaryOf(group.carol(), group.id()).get("lastMessage")).get("content");
        assertThat((String) JsonPath.read(carolLast, "$.event")).isEqualTo("MEMBER_REMOVED");
    }

    @Test
    void roleChangesBecomeAdminMessagesAndAnAutomaticPromotionHasNoActor() throws Exception {
        CreatedGroup group = createGroup();
        Map<String, Object> automatic = new HashMap<>();
        automatic.put("actorId", null);
        automatic.put("actorName", null);
        automatic.put("userId", group.bob().userId().toString());
        automatic.put("userName", "Bob");
        automatic.put("role", "ADMIN");
        automatic.put("at", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        publishEvent("GroupRoleChangedEvent", group.id(), automatic);
        publishEvent("GroupRoleChangedEvent", group.id(), Map.of(
                "actorId", group.bob().userId().toString(), "actorName", "Bob",
                "userId", group.carol().userId().toString(), "userName", "Carol",
                "role", "MEMBER", "at", Instant.now().truncatedTo(ChronoUnit.MICROS).toString()));
        awaitSystemMessages(group.id(), 3);

        List<Map<String, Object>> messages = systemMessages(group.id());
        String promoted = content(messages.get(1));
        assertThat((String) JsonPath.read(promoted, "$.event")).isEqualTo("ADMIN_ADDED");
        assertThat((Object) JsonPath.read(promoted, "$.actor")).isNull();
        assertThat((String) JsonPath.read(promoted, "$.users[0].id")).isEqualTo(group.bob().userId().toString());
        assertThat(messages.get(1).get("sender_id")).isEqualTo(group.bob().userId().toString());

        String dismissed = content(messages.get(2));
        assertThat((String) JsonPath.read(dismissed, "$.event")).isEqualTo("ADMIN_REMOVED");
        assertThat((String) JsonPath.read(dismissed, "$.actor.name")).isEqualTo("Bob");
        assertThat((String) JsonPath.read(dismissed, "$.users[0].name")).isEqualTo("Carol");
    }

    @Test
    void anUpdateWritesOneMessagePerChange() throws Exception {
        CreatedGroup group = createGroup();
        Map<String, Object> update = new HashMap<>();
        update.put("actorId", group.admin().userId().toString());
        update.put("actorName", "Asha");
        update.put("at", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        update.put("name", "Goa 2026");
        update.put("descriptionChanged", true);
        update.put("onlyAdminsCanSend", true);
        update.put("onlyAdminsCanEdit", null);
        publishEvent("GroupUpdatedEvent", group.id(), update);
        awaitSystemMessages(group.id(), 4);

        List<Map<String, Object>> messages = systemMessages(group.id());
        assertThat(messages).hasSize(4);
        assertThat((String) JsonPath.read(content(messages.get(1)), "$.event")).isEqualTo("NAME_CHANGED");
        assertThat((String) JsonPath.read(content(messages.get(1)), "$.name")).isEqualTo("Goa 2026");
        assertThat((String) JsonPath.read(content(messages.get(2)), "$.event")).isEqualTo("DESCRIPTION_CHANGED");
        String setting = content(messages.get(3));
        assertThat((String) JsonPath.read(setting, "$.event")).isEqualTo("SETTINGS_CHANGED");
        assertThat((String) JsonPath.read(setting, "$.setting")).isEqualTo("ONLY_ADMINS_CAN_SEND");
        assertThat((Boolean) JsonPath.read(setting, "$.value")).isTrue();
    }

    @Test
    void aRepeatedEventDoesNothingTheSecondTime() throws Exception {
        CreatedGroup group = createGroup();
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = Map.of(
                "actorId", group.admin().userId().toString(), "actorName", "Asha",
                "members", List.of(user(Device.newUser(), "Dave")),
                "joinedAt", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        publishEvent(eventId, "GroupMembersAddedEvent", group.id(), payload);
        publishEvent(eventId, "GroupMembersAddedEvent", group.id(), payload);
        // a later, different event proves the duplicate was already handled (same partition, so in order)
        publishEvent("GroupUpdatedEvent", group.id(), Map.of(
                "actorId", group.admin().userId().toString(), "actorName", "Asha",
                "at", Instant.now().truncatedTo(ChronoUnit.MICROS).toString(), "descriptionChanged", true));
        await("the description message", () -> systemMessages(group.id()).stream()
                .anyMatch(row -> content(row).contains("DESCRIPTION_CHANGED")));

        long added = systemMessages(group.id()).stream().filter(row -> content(row).contains("MEMBERS_ADDED")).count();
        assertThat(added).isEqualTo(1);
    }

    @Test
    void anOlderAnswerFromConversationServiceNeverReopensAPeriodClosedByAnEvent() throws Exception {
        CreatedGroup group = createGroup();
        // The event says Carol was removed; the fake conversation-service has not caught up (still lists her).
        Instant removedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        publishEvent("GroupMemberRemovedEvent", group.id(), Map.of(
                "actorId", group.admin().userId().toString(), "actorName", "Asha",
                "userId", group.carol().userId().toString(), "userName", "Carol",
                "reason", "REMOVED", "leftAt", removedAt.toString()));
        awaitSystemMessages(group.id(), 2);

        UUID later = sendOk(group.admin(), group.id(), "sent after the removal");   // syncs with the older answer

        assertThat(periods(group.id(), group.carol().userId())).hasSize(1);
        assertThat(periods(group.id(), group.carol().userId()).get(0).get("left_at")).isNotNull();
        List<String> carolSees = read(get("/api/conversations/" + group.id() + "/messages", group.carol()), "$.data.items[*].id");
        assertThat(carolSees).doesNotContain(later.toString());
    }

    @Test
    void aPrivateChatOnlyGetsMembershipPeriods() throws Exception {
        UUID id = UUID.randomUUID();
        Device a = Device.newUser();
        Device b = Device.newUser();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "PRIVATE");
        payload.put("participantIds", List.of(a.userId().toString(), b.userId().toString()));
        payload.put("createdAt", createdAt.toString());
        payload.put("createdBy", a.userId().toString());
        payload.put("groupName", null);
        payload.put("createdByName", null);
        publishEvent("ConversationCreatedEvent", id, payload);

        await("private periods", () -> periods(id, b.userId()).size() == 1);
        assertThat(periods(id, a.userId()).get(0).get("conversation_type")).isEqualTo("PRIVATE");
        assertThat(systemMessages(id)).isEmpty();
    }

    @Test
    void brokenEventsGoToTheDeadLetterTopic() throws Exception {
        String marker = UUID.randomUUID().toString();
        publishRaw(marker, "this is not json " + marker);
        UUID missingFields = publishEvent("GroupMemberRemovedEvent", UUID.randomUUID(), Map.of("reason", "REMOVED"));

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-dlt-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        boolean foundBroken = false;
        boolean foundMissing = false;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("conversation.events.dlt"));
            long deadline = System.currentTimeMillis() + 30_000;
            while ((!foundBroken || !foundMissing) && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    foundBroken |= record.value().contains(marker);
                    foundMissing |= record.value().contains(missingFields.toString());
                }
            }
        }
        assertThat(foundBroken).as("broken JSON on the dead-letter topic").isTrue();
        assertThat(foundMissing).as("event with missing fields on the dead-letter topic").isTrue();
    }
}
