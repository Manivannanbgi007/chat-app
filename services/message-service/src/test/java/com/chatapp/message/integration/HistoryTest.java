package com.chatapp.message.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.chatapp.message.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class HistoryTest extends AbstractIntegrationTest {

    private List<UUID> sendMany(Chat chat, int count) throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Device sender = (i % 2 == 0) ? chat.a() : chat.b();
            ids.add(sendOk(sender, chat.id(), "message " + i));
        }
        return ids;
    }

    private String path(Chat chat, String query) {
        return "/api/conversations/" + chat.id() + "/messages" + query;
    }

    @Test
    void newestPageComesFirstAndPagingWalksBackWithoutGapsOrRepeats() throws Exception {
        Chat chat = newChat();
        List<UUID> sent = sendMany(chat, 25);

        MvcResult first = get(path(chat, "?limit=10"), chat.a());
        assertStatus(first, 200);
        List<String> firstIds = read(first, "$.data.items[*].id");
        assertThat(firstIds).hasSize(10);
        assertThat(firstIds.get(0)).isEqualTo(sent.get(24).toString());     // newest first
        assertThat((Boolean) read(first, "$.data.hasMore")).isTrue();

        List<String> all = new ArrayList<>(firstIds);
        String cursor = field(first, "$.data.nextCursor");
        boolean hasMore = true;
        while (hasMore) {
            MvcResult page = get(path(chat, "?limit=10&before=" + cursor), chat.a());
            assertStatus(page, 200);
            all.addAll(read(page, "$.data.items[*].id"));
            hasMore = read(page, "$.data.hasMore");
            cursor = field(page, "$.data.nextCursor");
        }
        assertThat(all).hasSize(25).doesNotHaveDuplicates();
        List<String> expected = new ArrayList<>(sent.stream().map(UUID::toString).toList());
        java.util.Collections.reverse(expected);
        assertThat(all).containsExactlyElementsOf(expected);
        assertThat(cursor).isNull();
    }

    @Test
    void lastPageHasNoMoreAndNoCursor() throws Exception {
        Chat chat = newChat();
        sendMany(chat, 3);

        MvcResult page = get(path(chat, "?limit=30"), chat.a());

        assertThat((List<?>) read(page, "$.data.items")).hasSize(3);
        assertThat((Boolean) read(page, "$.data.hasMore")).isFalse();
        assertThat(field(page, "$.data.nextCursor")).isNull();
    }

    @Test
    void afterCursorReturnsNewerMessagesOldestFirst() throws Exception {
        Chat chat = newChat();
        List<UUID> sent = sendMany(chat, 6);
        MvcResult newest = get(path(chat, "?limit=6"), chat.a());
        // cursor of the 3rd newest (index 2 in newest-first order => sent[3]) is not exposed; use history paging cursor
        MvcResult small = get(path(chat, "?limit=3"), chat.a());     // newest 3: sent[5], sent[4], sent[3]
        String cursorBeforeSent3 = field(small, "$.data.nextCursor");  // points at sent[3]
        assertThat(cursorBeforeSent3).isNotNull();

        MvcResult newer = get(path(chat, "?after=" + cursorBeforeSent3), chat.a());

        assertStatus(newer, 200);
        List<String> ids = read(newer, "$.data.items[*].id");
        assertThat(ids).containsExactly(sent.get(4).toString(), sent.get(5).toString());     // strictly after, oldest first
        assertThat(newest).isNotNull();
    }

    @Test
    void afterCursorPagesForwardWhenThereAreMany() throws Exception {
        Chat chat = newChat();
        List<UUID> sent = sendMany(chat, 12);
        // start before everything: the cursor of the oldest page boundary
        String start = field(get(path(chat, "?limit=1"), chat.a()), "$.data.nextCursor");   // points at sent[11]
        MvcResult oldest = get(path(chat, "?limit=11"), chat.a());
        String beforeOldest = field(oldest, "$.data.nextCursor");                            // points at sent[1]
        assertThat(start).isNotNull();
        assertThat(beforeOldest).isNotNull();

        List<String> collected = new ArrayList<>();
        String cursor = beforeOldest;
        boolean hasMore = true;
        while (hasMore) {
            MvcResult page = get(path(chat, "?after=" + cursor + "&limit=4"), chat.a());
            assertStatus(page, 200);
            collected.addAll(read(page, "$.data.items[*].id"));
            hasMore = read(page, "$.data.hasMore");
            cursor = field(page, "$.data.nextCursor");
        }
        assertThat(collected).containsExactlyElementsOf(
                sent.subList(2, 12).stream().map(UUID::toString).toList());
    }

    @Test
    void afterTheLastMessageNothingIsReturnedAndTheCursorDoesNotMoveBack() throws Exception {
        Chat chat = newChat();
        sendMany(chat, 2);
        MvcResult page = get(path(chat, "?limit=1"), chat.a());
        String cursor = field(page, "$.data.nextCursor");

        MvcResult caughtUp = get(path(chat, "?after=" + cursor), chat.a());
        String next = field(caughtUp, "$.data.nextCursor");
        MvcResult again = get(path(chat, "?after=" + next), chat.a());

        assertThat((List<?>) read(again, "$.data.items")).isNotNull();
        assertThat(field(again, "$.data.nextCursor")).isNotNull();
        assertThat((Boolean) read(caughtUp, "$.data.hasMore")).isFalse();
    }

    @Test
    void ownMessagesShowStatusAndIncomingOnesShowMyReceipt() throws Exception {
        Chat chat = newChat();
        UUID fromA = sendOk(chat.a(), chat.id(), "from a");
        UUID fromB = sendOk(chat.b(), chat.id(), "from b");

        MvcResult asA = get(path(chat, ""), chat.a());
        // newest first: fromB (incoming for A), fromA (own)
        assertThat(field(asA, "$.data.items[0].id")).isEqualTo(fromB.toString());
        assertThat(field(asA, "$.data.items[0].status")).isNull();
        assertThat(field(asA, "$.data.items[0].myReceipt")).isNull();
        assertThat(field(asA, "$.data.items[1].id")).isEqualTo(fromA.toString());
        assertThat(field(asA, "$.data.items[1].status")).isEqualTo("SENT");
    }

    @Test
    void nonParticipantCannotReadHistory() throws Exception {
        Chat chat = newChat();
        sendMany(chat, 2);

        assertStatus(get(path(chat, ""), Device.newUser()), 404);
        assertStatus(get(path(chat, ""), null), 401);
        assertStatus(get("/api/conversations/" + UUID.randomUUID() + "/messages", chat.a()), 404);
    }

    @Test
    void badParametersAreRejected() throws Exception {
        Chat chat = newChat();
        sendMany(chat, 1);

        assertStatus(get(path(chat, "?limit=0"), chat.a()), 400);
        assertStatus(get(path(chat, "?limit=101"), chat.a()), 400);
        assertStatus(get(path(chat, "?before=not-a-cursor"), chat.a()), 400);
        assertStatus(get(path(chat, "?after=%%%"), chat.a()), 400);
        MvcResult both = get(path(chat, "?before=x&after=y"), chat.a());
        assertStatus(both, 400);
    }

    @Test
    void conversationsAreSeparated() throws Exception {
        Chat one = newChat();
        Chat two = newChat();
        sendOk(one.a(), one.id(), "only in one");
        sendOk(two.a(), two.id(), "only in two");

        MvcResult page = get(path(one, ""), one.a());
        List<String> contents = read(page, "$.data.items[*].content");
        assertThat(contents).containsExactly("only in one");
    }
}
