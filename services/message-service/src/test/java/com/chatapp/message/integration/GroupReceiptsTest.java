package com.chatapp.message.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chatapp.message.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Group message status (all recipients must have it) and "message info" (GET /api/messages/{id}/receipts).
 */
class GroupReceiptsTest extends AbstractIntegrationTest {

    private String statusSeenBySender(UUID conversationId, Device sender, UUID messageId) throws Exception {
        MvcResult page = get("/api/conversations/" + conversationId + "/messages", sender);
        List<String> ids = read(page, "$.data.items[*].id");
        int index = ids.indexOf(messageId.toString());
        assertThat(index).isNotNegative();
        return field(page, "$.data.items[" + index + "].status");
    }

    private Map<String, Map<String, Object>> receiptsByUser(Device viewer, UUID messageId) throws Exception {
        MvcResult result = get("/api/messages/" + messageId + "/receipts", viewer);
        assertStatus(result, 200);
        List<Map<String, Object>> rows = read(result, "$.data");
        Map<String, Map<String, Object>> byUser = new java.util.HashMap<>();
        rows.forEach(row -> byUser.put((String) row.get("userId"), row));
        return byUser;
    }

    @Test
    void theStatusWaitsForEveryRecipient() throws Exception {
        Group group = newGroup(2);
        Device bob = group.member(0);
        Device carol = group.member(1);
        UUID message = sendOk(group.admin(), group.id(), "hello both");

        markDelivered(bob, message);
        assertThat(statusSeenBySender(group.id(), group.admin(), message)).isEqualTo("SENT");

        markDelivered(carol, message);
        assertThat(statusSeenBySender(group.id(), group.admin(), message)).isEqualTo("DELIVERED");

        markRead(bob, group.id(), message);
        assertThat(statusSeenBySender(group.id(), group.admin(), message)).isEqualTo("DELIVERED");

        markRead(carol, group.id(), message);
        assertThat(statusSeenBySender(group.id(), group.admin(), message)).isEqualTo("READ");
    }

    @Test
    void inGroupsReadIsShownEvenWhenSomeoneTurnedReadReceiptsOff() throws Exception {
        Group group = newGroup(1);
        PEERS.setReadReceipts(group.member(0).userId(), false);
        UUID message = sendOk(group.admin(), group.id(), "hello");

        markRead(group.member(0), group.id(), message);

        assertThat(statusSeenBySender(group.id(), group.admin(), message)).isEqualTo("READ");
        PEERS.setReadReceipts(group.member(0).userId(), true);
    }

    @Test
    void aMemberWhoLeftNoLongerHoldsTheStatusBack() throws Exception {
        Group group = newGroup(2);
        UUID message = sendOk(group.admin(), group.id(), "hello both");
        markRead(group.member(0), group.id(), message);
        assertThat(statusSeenBySender(group.id(), group.admin(), message)).isEqualTo("SENT");

        PEERS.removeMember(group.id(), group.member(1).userId());

        assertThat(statusSeenBySender(group.id(), group.admin(), message)).isEqualTo("READ");
    }

    @Test
    void aMemberWhoJoinedLaterIsNotARecipientOfOlderMessages() throws Exception {
        Group group = newGroup(1);
        UUID message = sendOk(group.admin(), group.id(), "before Dave");
        markRead(group.member(0), group.id(), message);
        PEERS.addMember(group.id(), UUID.randomUUID());

        assertThat(statusSeenBySender(group.id(), group.admin(), message)).isEqualTo("READ");
        assertThat(receiptsByUser(group.admin(), message)).containsOnlyKeys(group.member(0).userId().toString());
    }

    @Test
    void recipientsSeeTheirOwnReceiptNotAStatus() throws Exception {
        Group group = newGroup(1);
        UUID message = sendOk(group.admin(), group.id(), "hello");
        markDelivered(group.member(0), message);

        MvcResult page = get("/api/conversations/" + group.id() + "/messages", group.member(0));
        assertThat(field(page, "$.data.items[0].status")).isNull();
        assertThat(field(page, "$.data.items[0].myReceipt")).isEqualTo("DELIVERED");
    }

    // ------------------------------------------------------------ message info

    @Test
    void messageInfoListsEveryRecipientWithTheirProgress() throws Exception {
        Group group = newGroup(3);
        UUID message = sendOk(group.admin(), group.id(), "who read this?");
        markRead(group.member(0), group.id(), message);
        markDelivered(group.member(1), message);
        markDelivered(group.member(1).otherDevice(), message);

        Map<String, Map<String, Object>> byUser = receiptsByUser(group.admin(), message);

        assertThat(byUser).hasSize(3);
        Map<String, Object> read = byUser.get(group.member(0).userId().toString());
        assertThat(read.get("status")).isEqualTo("READ");
        assertThat(read.get("readAt")).isNotNull();
        assertThat(read.get("deliveredAt")).isNotNull();
        Map<String, Object> delivered = byUser.get(group.member(1).userId().toString());
        assertThat(delivered.get("status")).isEqualTo("DELIVERED");
        assertThat(delivered.get("readAt")).isNull();
        Map<String, Object> waiting = byUser.get(group.member(2).userId().toString());
        assertThat(waiting.get("status")).isEqualTo("SENT");
        assertThat(waiting.get("deliveredAt")).isNull();

        // READ first, then DELIVERED, then SENT
        List<String> order = read(get("/api/messages/" + message + "/receipts", group.admin()), "$.data[*].status");
        assertThat(order).containsExactly("READ", "DELIVERED", "SENT");
    }

    @Test
    void onlyTheSenderMayAskForMessageInfo() throws Exception {
        Group group = newGroup(1);
        UUID message = sendOk(group.admin(), group.id(), "mine");

        assertStatus(get("/api/messages/" + message + "/receipts", group.member(0)), 404);
        assertStatus(get("/api/messages/" + message + "/receipts", Device.newUser()), 404);
        assertStatus(get("/api/messages/" + UUID.randomUUID() + "/receipts", group.admin()), 404);
        assertStatus(get("/api/messages/" + message + "/receipts", null), 401);
    }

    @Test
    void privateChatMessageInfoHidesAReadThatPrivacyHides() throws Exception {
        Chat chat = newChat();
        UUID message = sendOk(chat.a(), chat.id(), "hello");
        PEERS.setReadReceipts(chat.b().userId(), false);
        markRead(chat.b(), chat.id(), message);

        Map<String, Object> bob = receiptsByUser(chat.a(), message).get(chat.b().userId().toString());
        assertThat(bob.get("status")).isEqualTo("DELIVERED");
        assertThat(bob.get("readAt")).isNull();
        assertThat(bob.get("deliveredAt")).isNotNull();

        PEERS.setReadReceipts(chat.b().userId(), true);
        Map<String, Object> visible = receiptsByUser(chat.a(), message).get(chat.b().userId().toString());
        assertThat(visible.get("status")).isEqualTo("READ");
        assertThat(visible.get("readAt")).isNotNull();
    }
}
