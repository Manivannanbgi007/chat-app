package com.chatapp.message.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chatapp.message.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Groups: who may send, and the removed-member rule (a member only sees messages from their own membership periods).
 * Critical scenarios 8 (group delivery only to members) and 9 (removed members) - see TESTING_GUIDE.md.
 */
class GroupMessagingTest extends AbstractIntegrationTest {

    @AfterEach
    void reset() {
        PEERS.unblockAll();
    }

    private List<String> historyIds(UUID conversationId, Device viewer) throws Exception {
        MvcResult page = get("/api/conversations/" + conversationId + "/messages?limit=100", viewer);
        assertStatus(page, 200);
        return read(page, "$.data.items[*].id");
    }

    private List<String> syncIds(Device viewer, String cursor) throws Exception {
        MvcResult page = get("/api/messages/sync?limit=100&after=" + cursor, viewer);
        assertStatus(page, 200);
        return read(page, "$.data.items[*].id");
    }

    private String syncStart(Device viewer) throws Exception {
        return field(get("/api/messages/sync", viewer), "$.data.nextCursor");
    }

    private Map<String, Object> summaryOf(Device viewer, UUID conversationId) throws Exception {
        List<Map<String, Object>> rows = read(get("/api/messages/summaries", viewer),
                "$.data[?(@.conversationId=='" + conversationId + "')]");
        assertThat(rows).as("summary of " + conversationId).hasSize(1);
        return rows.get(0);
    }

    // ----------------------------------------------------------------- sending

    @Test
    void everyCurrentMemberMaySendAndEveryMemberSeesIt() throws Exception {
        Group group = newGroup(2);
        UUID fromAdmin = sendOk(group.admin(), group.id(), "hello group");
        UUID fromMember = sendOk(group.member(0), group.id(), "hi all");

        for (Device viewer : List.of(group.admin(), group.member(0), group.member(1))) {
            assertThat(historyIds(group.id(), viewer)).containsExactly(fromMember.toString(), fromAdmin.toString());
        }
        assertStatus(get("/api/conversations/" + group.id() + "/messages", Device.newUser()), 404);
    }

    @Test
    void theKafkaEventNamesEveryOtherCurrentMemberAsRecipient() throws Exception {
        Group group = newGroup(2);
        UUID id = sendOk(group.admin(), group.id(), "hello");

        String payload = jdbc.queryForObject("SELECT payload FROM outbox_events WHERE event_type = 'MessageCreatedEvent' "
                + "AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.payload.messageId')) = ?", String.class, id.toString());
        List<String> recipients = com.jayway.jsonpath.JsonPath.read(payload, "$.payload.recipientIds");
        assertThat(recipients).containsExactlyInAnyOrder(group.member(0).userId().toString(), group.member(1).userId().toString());
    }

    @Test
    void onlyAdminsCanSendWhenTheGroupSaysSo() throws Exception {
        Group group = newGroup(1);
        PEERS.setOnlyAdminsCanSend(group.id(), true);

        MvcResult member = send(group.member(0), group.id(), "may I?", newKey());
        assertStatus(member, 403);
        assertThat(field(member, "$.data.errorCode")).isEqualTo("ONLY_ADMINS_CAN_SEND");

        assertStatus(send(group.admin(), group.id(), "admins only", newKey()), 201);

        PEERS.setAdmins(group.id(), group.admin().userId(), group.member(0).userId());
        assertStatus(send(group.member(0), group.id(), "now I am an admin", newKey()), 201);
    }

    @Test
    void blockingDoesNotApplyInsideGroups() throws Exception {
        Group group = newGroup(1);
        PEERS.block(group.member(0).userId(), group.admin().userId());     // the member blocked the admin

        assertStatus(send(group.admin(), group.id(), "still in the group", newKey()), 201);
        assertStatus(send(group.member(0), group.id(), "me too", newKey()), 201);
    }

    @Test
    void clientsCannotSendSystemMessages() throws Exception {
        Group group = newGroup(1);
        MvcResult result = post("/api/messages", Map.of("conversationId", group.id(), "type", "SYSTEM",
                "content", "{\"event\":\"GROUP_CREATED\"}", "idempotencyKey", newKey()), group.admin());
        assertStatus(result, 400);
        assertThat(field(result, "$.data.errorCode")).isEqualTo("UNSUPPORTED_TYPE");
    }

    // ------------------------------------------------------- removed members

    @Test
    void aRemovedMemberKeepsTheOldMessagesButSeesNothingNewer() throws Exception {
        Group group = newGroup(2);
        Device carol = group.member(1);
        String carolCursor = syncStart(carol);
        UUID before = sendOk(group.admin(), group.id(), "before Carol was removed");

        PEERS.removeMember(group.id(), carol.userId());
        UUID after = sendOk(group.admin(), group.id(), "after Carol was removed");

        // history
        assertThat(historyIds(group.id(), carol)).containsExactly(before.toString());
        assertThat(historyIds(group.id(), group.member(0))).containsExactly(after.toString(), before.toString());
        MvcResult newer = get("/api/conversations/" + group.id() + "/messages?after="
                + field(get("/api/messages/sync", carol), "$.data.nextCursor"), carol);
        assertThat((List<String>) read(newer, "$.data.items[*].id")).doesNotContain(after.toString());

        // sync
        assertThat(syncIds(carol, carolCursor)).contains(before.toString()).doesNotContain(after.toString());

        // summaries: the group is still listed, with the last message from her time and one unread
        Map<String, Object> summary = summaryOf(carol, group.id());
        assertThat(((Map<?, ?>) summary.get("lastMessage")).get("id")).isEqualTo(before.toString());
        assertThat(((Number) summary.get("unreadCount")).intValue()).isEqualTo(1);
    }

    @Test
    void aRemovedMemberCannotSendAcknowledgeOrReadNewerMessages() throws Exception {
        Group group = newGroup(2);
        Device carol = group.member(1);
        UUID before = sendOk(group.admin(), group.id(), "before");
        PEERS.removeMember(group.id(), carol.userId());
        UUID after = sendOk(group.admin(), group.id(), "after");

        MvcResult sendAttempt = send(carol, group.id(), "am I still here?", newKey());
        assertStatus(sendAttempt, 404);

        assertThat(field(markDelivered(carol, after), "$.data.updated")).isEqualTo("0");
        assertStatus(markRead(carol, group.id(), after), 404);

        // her own old messages: still allowed
        assertThat(field(markDelivered(carol, before), "$.data.updated")).isEqualTo("1");
        assertThat(field(markRead(carol, group.id(), before), "$.data.updated")).isEqualTo("1");
        Integer receiptsForAfter = jdbc.queryForObject("SELECT COUNT(*) FROM message_receipts WHERE message_id = ?",
                Integer.class, after.toString());
        assertThat(receiptsForAfter).isZero();
    }

    @Test
    void aMemberAddedAgainSeesBothPeriodsButNotTheGap() throws Exception {
        Group group = newGroup(2);
        Device carol = group.member(1);
        UUID first = sendOk(group.admin(), group.id(), "first period");
        PEERS.removeMember(group.id(), carol.userId());
        UUID gap = sendOk(group.admin(), group.id(), "while Carol was out");
        PEERS.addMember(group.id(), carol.userId());
        UUID second = sendOk(group.admin(), group.id(), "second period");

        assertThat(historyIds(group.id(), carol)).containsExactly(second.toString(), first.toString());
        assertStatus(send(carol, group.id(), "back again", newKey()), 201);

        Integer periods = jdbc.queryForObject("SELECT COUNT(*) FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                Integer.class, group.id().toString(), carol.userId().toString());
        assertThat(periods).isEqualTo(2);
        assertThat(historyIds(group.id(), group.admin())).contains(gap.toString());
    }

    @Test
    void aNewMemberDoesNotSeeOlderMessages() throws Exception {
        Group group = newGroup(1);
        UUID old = sendOk(group.admin(), group.id(), "before Dave joined");
        Device dave = Device.newUser();
        PEERS.addMember(group.id(), dave.userId());
        UUID fresh = sendOk(group.admin(), group.id(), "welcome Dave");

        assertThat(historyIds(group.id(), dave)).containsExactly(fresh.toString());
        assertThat(historyIds(group.id(), group.member(0))).containsExactly(fresh.toString(), old.toString());
        assertStatus(markRead(dave, group.id(), old), 404);
        Map<String, Object> summary = summaryOf(dave, group.id());
        assertThat(((Number) summary.get("unreadCount")).intValue()).isEqualTo(1);
    }

    @Test
    void aFormerMemberCannotTypeOrSendButNobodyElseLearnsTheGroupExists() throws Exception {
        Group group = newGroup(1);
        PEERS.removeMember(group.id(), group.member(0).userId());

        MvcResult former = send(group.member(0), group.id(), "hello?", newKey());
        MvcResult stranger = send(Device.newUser(), group.id(), "hello?", newKey());
        assertStatus(former, 404);
        assertStatus(stranger, 404);
        assertThat(field(former, "$.message")).isEqualTo(field(stranger, "$.message"));
    }
}
