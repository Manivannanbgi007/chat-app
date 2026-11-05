package com.chatapp.message.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.chatapp.message.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class SummariesAndSyncTest extends AbstractIntegrationTest {

    private List<Integer> unreadOf(MvcResult summaries, UUID conversationId) throws Exception {
        return read(summaries, "$.data[?(@.conversationId=='" + conversationId + "')].unreadCount");
    }

    // -------------------------------------------------------------- summaries

    @Test
    void summaryShowsLastMessageAndUnreadCountForTheRecipient() throws Exception {
        Chat chat = newChat();
        sendOk(chat.a(), chat.id(), "first");
        UUID last = sendOk(chat.a(), chat.id(), "second");

        MvcResult asBob = get("/api/messages/summaries", chat.b());
        assertStatus(asBob, 200);
        assertThat(unreadOf(asBob, chat.id())).containsExactly(2);
        List<String> lastIds = read(asBob, "$.data[?(@.conversationId=='" + chat.id() + "')].lastMessage.id");
        assertThat(lastIds).containsExactly(last.toString());
        List<String> lastTexts = read(asBob, "$.data[?(@.conversationId=='" + chat.id() + "')].lastMessage.content");
        assertThat(lastTexts).containsExactly("second");
    }

    @Test
    void ownMessagesAreNeverUnreadForTheSender() throws Exception {
        Chat chat = newChat();
        sendOk(chat.a(), chat.id(), "mine");

        MvcResult asAlice = get("/api/messages/summaries", chat.a());
        assertThat(unreadOf(asAlice, chat.id())).containsExactly(0);
    }

    @Test
    void readingClearsTheUnreadCount() throws Exception {
        Chat chat = newChat();
        sendOk(chat.a(), chat.id(), "first");
        UUID last = sendOk(chat.a(), chat.id(), "second");

        markRead(chat.b(), chat.id(), last);

        assertThat(unreadOf(get("/api/messages/summaries", chat.b()), chat.id())).containsExactly(0);
    }

    @Test
    void deliveredButNotReadStillCountsAsUnread() throws Exception {
        Chat chat = newChat();
        UUID message = sendOk(chat.a(), chat.id(), "hello");
        markDelivered(chat.b(), message);

        assertThat(unreadOf(get("/api/messages/summaries", chat.b()), chat.id())).containsExactly(1);
    }

    @Test
    void conversationsWithoutMessagesAndForeignConversationsAreNotListed() throws Exception {
        Chat empty = newChat();
        Chat busy = newChat();
        sendOk(busy.a(), busy.id(), "hello");

        MvcResult asStranger = get("/api/messages/summaries", Device.newUser());
        assertThat((List<?>) read(asStranger, "$.data")).isEmpty();

        MvcResult asAlice = get("/api/messages/summaries", busy.a());
        List<String> ids = read(asAlice, "$.data[*].conversationId");
        assertThat(ids).contains(busy.id().toString()).doesNotContain(empty.id().toString());
        assertStatus(get("/api/messages/summaries", null), 401);
    }

    @Test
    void lastMessageStatusIsShownToItsSender() throws Exception {
        Chat chat = newChat();
        UUID message = sendOk(chat.a(), chat.id(), "hello");
        markDelivered(chat.b(), message);

        MvcResult asAlice = get("/api/messages/summaries", chat.a());
        List<String> status = read(asAlice, "$.data[?(@.conversationId=='" + chat.id() + "')].lastMessage.status");
        assertThat(status).containsExactly("DELIVERED");
    }

    // ------------------------------------------------------------------- sync

    @Test
    void syncWithoutCursorIsEmptyAndGivesAStartingPoint() throws Exception {
        Chat chat = newChat();
        sendOk(chat.a(), chat.id(), "old news");

        MvcResult result = get("/api/messages/sync", chat.b());

        assertStatus(result, 200);
        assertThat((List<?>) read(result, "$.data.items")).isEmpty();
        assertThat(field(result, "$.data.nextCursor")).isNotBlank();
        assertThat((Boolean) read(result, "$.data.hasMore")).isFalse();
    }

    @Test
    void offlineRecipientCatchesUpWithSync() throws Exception {
        Chat chat = newChat();
        sendOk(chat.a(), chat.id(), "before Bob went offline");     // makes the members known
        String cursor = field(get("/api/messages/sync", chat.b()), "$.data.nextCursor");

        // Bob is offline; Alice keeps writing
        UUID m1 = sendOk(chat.a(), chat.id(), "while you were away 1");
        UUID m2 = sendOk(chat.a(), chat.id(), "while you were away 2");

        MvcResult sync = get("/api/messages/sync?after=" + cursor, chat.b());

        assertStatus(sync, 200);
        List<String> ids = read(sync, "$.data.items[*].id");
        assertThat(ids).contains(m1.toString(), m2.toString());
        assertThat(ids.indexOf(m1.toString())).isLessThan(ids.indexOf(m2.toString()));      // oldest first
        // incoming messages are shown as not yet acknowledged, so the client knows to send "delivered"
        List<Object> receipts = read(sync, "$.data.items[?(@.id=='" + m1 + "')].myReceipt");
        assertThat(receipts).hasSize(1);
        assertThat(receipts.get(0)).isNull();

        // the cursor moves forward and a second sync does not lose or invent anything
        String next = field(sync, "$.data.nextCursor");
        MvcResult again = get("/api/messages/sync?after=" + next, chat.b());
        List<String> againIds = read(again, "$.data.items[*].id");
        assertThat(againIds).allMatch(id -> ids.contains(id));        // only repeats (client ignores duplicates)
    }

    @Test
    void syncIncludesMyOwnMessagesFromOtherDevices() throws Exception {
        Chat chat = newChat();
        sendOk(chat.a(), chat.id(), "setup");
        Device phone = chat.a();
        Device laptop = phone.otherDevice();
        String cursor = field(get("/api/messages/sync", laptop), "$.data.nextCursor");

        UUID sentFromPhone = sendOk(phone, chat.id(), "sent from my phone");

        MvcResult sync = get("/api/messages/sync?after=" + cursor, laptop);
        List<String> ids = read(sync, "$.data.items[*].id");
        assertThat(ids).contains(sentFromPhone.toString());
        List<String> statuses = read(sync, "$.data.items[?(@.id=='" + sentFromPhone + "')].status");
        assertThat(statuses).containsExactly("SENT");
    }

    @Test
    void syncNeverIncludesOtherPeoplesConversations() throws Exception {
        Chat mine = newChat();
        Chat theirs = newChat();
        sendOk(mine.a(), mine.id(), "setup");
        String cursor = field(get("/api/messages/sync", mine.b()), "$.data.nextCursor");

        sendOk(theirs.a(), theirs.id(), "private to someone else");
        UUID visible = sendOk(mine.a(), mine.id(), "for me");

        MvcResult sync = get("/api/messages/sync?after=" + cursor, mine.b());
        List<String> ids = read(sync, "$.data.items[*].id");
        List<String> conversations = read(sync, "$.data.items[*].conversationId");
        assertThat(ids).contains(visible.toString());
        assertThat(conversations).containsOnly(mine.id().toString());
        assertThat((List<?>) read(get("/api/messages/sync?after=" + cursor, Device.newUser()), "$.data.items")).isEmpty();
    }

    @Test
    void syncPagesForwardWithoutSkippingMessages() throws Exception {
        Chat chat = newChat();
        sendOk(chat.a(), chat.id(), "setup");
        String cursor = field(get("/api/messages/sync", chat.b()), "$.data.nextCursor");
        List<UUID> sent = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            sent.add(sendOk(chat.a(), chat.id(), "n" + i));
        }

        List<String> collected = new ArrayList<>();
        boolean hasMore = true;
        int guard = 0;
        while (hasMore && guard++ < 10) {
            MvcResult page = get("/api/messages/sync?limit=3&after=" + cursor, chat.b());
            assertStatus(page, 200);
            collected.addAll(read(page, "$.data.items[*].id"));
            hasMore = read(page, "$.data.hasMore");
            cursor = field(page, "$.data.nextCursor");
        }
        assertThat(hasMore).isFalse();
        assertThat(collected).containsAll(sent.stream().map(UUID::toString).toList());
    }

    @Test
    void syncRejectsBadInput() throws Exception {
        Chat chat = newChat();
        assertStatus(get("/api/messages/sync?after=garbage", chat.a()), 400);
        assertStatus(get("/api/messages/sync?limit=0", chat.a()), 400);
        assertStatus(get("/api/messages/sync", null), 401);
    }
}
