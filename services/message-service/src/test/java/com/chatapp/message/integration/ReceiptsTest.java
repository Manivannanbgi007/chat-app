package com.chatapp.message.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.chatapp.message.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** SENT -> DELIVERED -> READ, only when a recipient says so, only forward, per device. */
class ReceiptsTest extends AbstractIntegrationTest {

    @AfterEach
    void resetPrivacy() {
        PEERS.conversationServiceDown = false;
    }

    private String statusSeenBySender(Chat chat, UUID messageId) throws Exception {
        MvcResult page = get("/api/conversations/" + chat.id() + "/messages", chat.a());
        List<String> ids = read(page, "$.data.items[*].id");
        int index = ids.indexOf(messageId.toString());
        return field(page, "$.data.items[" + index + "].status");
    }

    private String receiptSeenByRecipient(Chat chat, Device viewer, UUID messageId) throws Exception {
        MvcResult page = get("/api/conversations/" + chat.id() + "/messages", viewer);
        List<String> ids = read(page, "$.data.items[*].id");
        int index = ids.indexOf(messageId.toString());
        return field(page, "$.data.items[" + index + "].myReceipt");
    }

    private int updated(MvcResult result) throws Exception {
        assertStatus(result, 200);
        return Integer.parseInt(field(result, "$.data.updated"));
    }

    private int countOutbox(String eventType, UUID conversationId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = ? AND aggregate_id = ?",
                Integer.class, eventType, conversationId.toString());
        return count == null ? 0 : count;
    }

    // -------------------------------------------------------------- delivered

    @Test
    void nothingBecomesDeliveredOrReadByItself() throws Exception {
        Chat chat = newChat();
        UUID message = sendOk(chat.a(), chat.id(), "hello");

        // Bob fetches the history and the chat list: that is NOT an acknowledgement
        get("/api/conversations/" + chat.id() + "/messages", chat.b());
        get("/api/messages/summaries", chat.b());

        assertThat(statusSeenBySender(chat, message)).isEqualTo("SENT");
        assertThat(receiptSeenByRecipient(chat, chat.b(), message)).isNull();
    }

    @Test
    void recipientAcknowledgesDeliveryAndTheSenderSeesIt() throws Exception {
        Chat chat = newChat();
        UUID message = sendOk(chat.a(), chat.id(), "hello");

        assertThat(updated(markDelivered(chat.b(), message))).isEqualTo(1);

        assertThat(statusSeenBySender(chat, message)).isEqualTo("DELIVERED");
        assertThat(receiptSeenByRecipient(chat, chat.b(), message)).isEqualTo("DELIVERED");
    }

    @Test
    void acknowledgingTwiceChangesNothing() throws Exception {
        Chat chat = newChat();
        UUID message = sendOk(chat.a(), chat.id(), "hello");

        assertThat(updated(markDelivered(chat.b(), message))).isEqualTo(1);
        assertThat(updated(markDelivered(chat.b(), message))).isZero();

        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM message_receipts WHERE message_id = ?", Integer.class, message.toString());
        assertThat(rows).isEqualTo(1);
        assertThat(countOutbox("MessageDeliveredEvent", chat.id())).isEqualTo(1);
    }

    @Test
    void theSenderCannotAcknowledgeTheirOwnMessage() throws Exception {
        Chat chat = newChat();
        UUID message = sendOk(chat.a(), chat.id(), "hello");

        assertThat(updated(markDelivered(chat.a(), message))).isZero();
        assertThat(statusSeenBySender(chat, message)).isEqualTo("SENT");
    }

    @Test
    void aStrangerCannotAcknowledgeSomeoneElsesConversation() throws Exception {
        Chat chat = newChat();
        UUID message = sendOk(chat.a(), chat.id(), "hello");

        assertThat(updated(markDelivered(Device.newUser(), message))).isZero();
        assertThat(statusSeenBySender(chat, message)).isEqualTo("SENT");
    }

    @Test
    void unknownMessageIdsAreIgnoredAndBadBodiesRejected() throws Exception {
        Chat chat = newChat();
        assertThat(updated(markDelivered(chat.b(), UUID.randomUUID()))).isZero();

        assertStatus(post("/api/messages/delivered", java.util.Map.of("messageIds", List.of()), chat.b()), 400);
        assertStatus(post("/api/messages/delivered", java.util.Map.of(), chat.b()), 400);
        assertStatus(post("/api/messages/delivered", java.util.Map.of("messageIds", List.of(UUID.randomUUID())), null), 401);
    }

    @Test
    void severalMessagesCanBeAcknowledgedAtOnce() throws Exception {
        Chat chat = newChat();
        UUID m1 = sendOk(chat.a(), chat.id(), "one");
        UUID m2 = sendOk(chat.a(), chat.id(), "two");
        UUID m3 = sendOk(chat.a(), chat.id(), "three");

        assertThat(updated(markDelivered(chat.b(), m1, m2, m3))).isEqualTo(3);
    }

    // ------------------------------------------------------------------- read

    @Test
    void readMarksMessagesReadAndImpliesDelivered() throws Exception {
        Chat chat = newChat();
        UUID message = sendOk(chat.a(), chat.id(), "hello");

        // never acknowledged as delivered before: read includes it
        assertThat(updated(markRead(chat.b(), chat.id(), message))).isEqualTo(1);

        assertThat(statusSeenBySender(chat, message)).isEqualTo("READ");
        assertThat(receiptSeenByRecipient(chat, chat.b(), message)).isEqualTo("READ");
        assertThat(countOutbox("MessageReadEvent", chat.id())).isEqualTo(1);
    }

    @Test
    void readOnlyCoversMessagesUpToTheGivenOne() throws Exception {
        Chat chat = newChat();
        UUID m1 = sendOk(chat.a(), chat.id(), "one");
        UUID m2 = sendOk(chat.a(), chat.id(), "two");
        UUID m3 = sendOk(chat.a(), chat.id(), "three");

        assertThat(updated(markRead(chat.b(), chat.id(), m2))).isEqualTo(2);

        assertThat(statusSeenBySender(chat, m1)).isEqualTo("READ");
        assertThat(statusSeenBySender(chat, m2)).isEqualTo("READ");
        assertThat(statusSeenBySender(chat, m3)).isEqualTo("SENT");
        // reading again changes nothing
        assertThat(updated(markRead(chat.b(), chat.id(), m2))).isZero();
    }

    @Test
    void readAfterDeliveredUpgradesTheSameReceipt() throws Exception {
        Chat chat = newChat();
        UUID message = sendOk(chat.a(), chat.id(), "hello");
        markDelivered(chat.b(), message);

        assertThat(updated(markRead(chat.b(), chat.id(), message))).isEqualTo(1);

        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM message_receipts WHERE message_id = ?", Integer.class, message.toString());
        assertThat(rows).isEqualTo(1);
        assertThat(statusSeenBySender(chat, message)).isEqualTo("READ");
    }

    @Test
    void statusNeverGoesBackwards() throws Exception {
        Chat chat = newChat();
        UUID message = sendOk(chat.a(), chat.id(), "hello");
        markRead(chat.b(), chat.id(), message);

        // a late "delivered" from the same device must not downgrade
        assertThat(updated(markDelivered(chat.b(), message))).isZero();

        assertThat(statusSeenBySender(chat, message)).isEqualTo("READ");
        assertThat(receiptSeenByRecipient(chat, chat.b(), message)).isEqualTo("READ");
    }

    @Test
    void onlyTheRecipientsMessagesAreReadNotTheirOwn() throws Exception {
        Chat chat = newChat();
        UUID fromA = sendOk(chat.a(), chat.id(), "from a");
        UUID fromB = sendOk(chat.b(), chat.id(), "from b");

        // Bob reads up to the newest message: only Alice's message gets a receipt
        assertThat(updated(markRead(chat.b(), chat.id(), fromB))).isEqualTo(1);

        assertThat(statusSeenBySender(chat, fromA)).isEqualTo("READ");
        Integer ownReceipts = jdbc.queryForObject("SELECT COUNT(*) FROM message_receipts WHERE message_id = ?", Integer.class, fromB.toString());
        assertThat(ownReceipts).isZero();
    }

    @Test
    void readCheckRejectsStrangersUnknownAndForeignMessages() throws Exception {
        Chat chat = newChat();
        Chat other = newChat();
        UUID message = sendOk(chat.a(), chat.id(), "hello");
        UUID foreign = sendOk(other.a(), other.id(), "elsewhere");

        assertStatus(markRead(Device.newUser(), chat.id(), message), 404);                 // not a participant
        assertStatus(markRead(chat.b(), chat.id(), UUID.randomUUID()), 404);               // unknown message
        assertStatus(markRead(chat.b(), chat.id(), foreign), 404);                          // message of another conversation
        assertStatus(post("/api/conversations/" + chat.id() + "/read", java.util.Map.of(), chat.b()), 400);
    }

    // ---------------------------------------------------------------- privacy

    @Test
    void readReceiptsOffForTheRecipientHideReadFromTheSender() throws Exception {
        Chat chat = newChat();
        PEERS.setReadReceipts(chat.b().userId(), false);
        UUID message = sendOk(chat.a(), chat.id(), "hello");

        markRead(chat.b(), chat.id(), message);

        assertThat(statusSeenBySender(chat, message)).isEqualTo("DELIVERED");      // read, but not shown
        assertThat(receiptSeenByRecipient(chat, chat.b(), message)).isEqualTo("READ");   // Bob still knows he read it
        PEERS.setReadReceipts(chat.b().userId(), true);
    }

    @Test
    void readReceiptsOffForTheSenderAlsoHideReadFromThem() throws Exception {
        Chat chat = newChat();
        PEERS.setReadReceipts(chat.a().userId(), false);
        UUID message = sendOk(chat.a(), chat.id(), "hello");

        markRead(chat.b(), chat.id(), message);

        assertThat(statusSeenBySender(chat, message)).isEqualTo("DELIVERED");      // same rule as WhatsApp: both or none
        PEERS.setReadReceipts(chat.a().userId(), true);
    }

    @Test
    void readReceiptsShowWhenBothAllow() throws Exception {
        Chat chat = newChat();
        UUID message = sendOk(chat.a(), chat.id(), "hello");
        markRead(chat.b(), chat.id(), message);
        assertThat(statusSeenBySender(chat, message)).isEqualTo("READ");
    }

    // ---------------------------------------------------------- several devices

    @Test
    void anyDeviceOfTheRecipientCanDeliverAndReadAndUnreadClearsEverywhere() throws Exception {
        Chat chat = newChat();
        Device bobPhone = chat.b();
        Device bobLaptop = bobPhone.otherDevice();
        UUID message = sendOk(chat.a(), chat.id(), "hello");

        assertThat(updated(markDelivered(bobPhone, message))).isEqualTo(1);
        assertThat(updated(markDelivered(bobLaptop, message))).isEqualTo(1);        // separate device, separate receipt
        assertThat(statusSeenBySender(chat, message)).isEqualTo("DELIVERED");

        // read on the laptop only
        markRead(bobLaptop, chat.id(), message);
        assertThat(statusSeenBySender(chat, message)).isEqualTo("READ");

        // the phone has nothing unread either: read on any device counts
        MvcResult summaries = get("/api/messages/summaries", bobPhone);
        List<Integer> counts = read(summaries, "$.data[?(@.conversationId=='" + chat.id() + "')].unreadCount");
        assertThat(counts).containsExactly(0);
    }
}
