package com.chatapp.status.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.chatapp.status.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Views and viewer lists, following read receipts as in WhatsApp. */
class ViewersTest extends AbstractIntegrationTest {

    private void view(Person viewer, UUID statusId) throws Exception {
        assertStatus(post("/api/statuses/" + statusId + "/view", null, viewer), 200);
    }

    @Test
    void viewsAreListedNewestFirstWithProfilesAndCountedInMine() throws Exception {
        Person owner = newPerson("Owner");
        Person first = newPerson("First");
        Person second = newPerson("Second");
        contact(owner, first);
        contact(owner, second);
        UUID statusId = textStatus(owner, "Look");
        UUID other = textStatus(owner, "Nobody looked");

        view(first, statusId);
        view(second, statusId);
        view(first, statusId);            // again: still one view, and the first time is kept

        MvcResult viewers = get("/api/statuses/" + statusId + "/viewers", owner);
        assertStatus(viewers, 200);
        assertThat(field(viewers, "$.data.viewersHidden")).isEqualTo("false");
        assertThat(field(viewers, "$.data.viewCount")).isEqualTo("2");
        List<String> ids = read(viewers, "$.data.viewers[*].user.id");
        assertThat(ids).containsExactly(second.id().toString(), first.id().toString());
        assertThat(field(viewers, "$.data.viewers[0].user.displayName")).isEqualTo(second.name());
        assertThat(field(viewers, "$.data.viewers[0].viewedAt")).isNotNull();
        assertThat(rows("SELECT COUNT(*) FROM status_views WHERE status_id = ?", statusId.toString())).isEqualTo(2);

        MvcResult mine = get("/api/statuses/mine", owner);
        assertStatus(mine, 200);
        List<String> mineIds = read(mine, "$.data[*].id");
        assertThat(mineIds).containsExactly(statusId.toString(), other.toString());     // oldest first
        assertThat(field(mine, "$.data[0].viewCount")).isEqualTo("2");
        assertThat(field(mine, "$.data[1].viewCount")).isEqualTo("0");
        assertThat(field(mine, "$.data[0].viewedByMe")).isEqualTo("true");
        assertThat(field(mine, "$.data[0].text")).isEqualTo("Look");
    }

    @Test
    void aViewerWithReadReceiptsOffIsRecordedButNotShownToTheOwner() throws Exception {
        Person owner = newPerson("Owner");
        Person open = newPerson("Open");
        Person shy = newPerson("Shy");
        contact(owner, open);
        contact(owner, shy);
        USERS.setReadReceipts(shy.id(), false);
        UUID statusId = textStatus(owner, "Hi");

        view(open, statusId);
        view(shy, statusId);

        MvcResult viewers = get("/api/statuses/" + statusId + "/viewers", owner);
        assertThat(field(viewers, "$.data.viewCount")).isEqualTo("1");
        List<String> ids = read(viewers, "$.data.viewers[*].user.id");
        assertThat(ids).containsExactly(open.id().toString());
        assertThat(field(get("/api/statuses/mine", owner), "$.data[0].viewCount")).isEqualTo("1");

        // ... but the shy viewer's own feed shows it as seen
        MvcResult feed = get("/api/statuses/feed", shy);
        assertThat(field(feed, "$.data[0].statuses[0].viewedByMe")).isEqualTo("true");
        assertThat(field(feed, "$.data[0].allViewed")).isEqualTo("true");
    }

    @Test
    void anOwnerWithReadReceiptsOffSeesNoViewersAtAll() throws Exception {
        Person owner = newPerson("Owner");
        Person viewer = newPerson("Viewer");
        contact(owner, viewer);
        USERS.setReadReceipts(owner.id(), false);
        UUID statusId = textStatus(owner, "Hi");
        view(viewer, statusId);

        MvcResult viewers = get("/api/statuses/" + statusId + "/viewers", owner);
        assertStatus(viewers, 200);
        assertThat(field(viewers, "$.data.viewersHidden")).isEqualTo("true");
        assertThat(field(viewers, "$.data.viewCount")).isEqualTo("0");
        assertThat((List<?>) read(viewers, "$.data.viewers")).isEmpty();
        assertThat(field(get("/api/statuses/mine", owner), "$.data[0].viewCount")).isEqualTo("0");
    }

    @Test
    void aViewerUserServiceNoLongerKnowsIsHidden() throws Exception {
        Person owner = newPerson("Owner");
        Person viewer = newPerson("Viewer");
        contact(owner, viewer);
        UUID statusId = textStatus(owner, "Hi");
        view(viewer, statusId);
        // read receipts are asked per viewer: an unknown viewer counts as "off" and is hidden (fail safe)
        USERS.forgetUser(viewer.id());

        MvcResult viewers = get("/api/statuses/" + statusId + "/viewers", owner);
        assertThat(field(viewers, "$.data.viewCount")).isEqualTo("0");
        assertThat((List<?>) read(viewers, "$.data.viewers")).isEmpty();
    }

    @Test
    void aViewerWhoBlockedTheOwnerLaterStaysListedAsUnknownSoTheCountMatches() throws Exception {
        Person owner = newPerson("Owner");
        Person viewer = newPerson("Viewer");
        contact(owner, viewer);
        UUID statusId = textStatus(owner, "Hi");
        view(viewer, statusId);
        USERS.block(viewer.id(), owner.id());    // user-service no longer shows the viewer's profile to the owner

        MvcResult viewers = get("/api/statuses/" + statusId + "/viewers", owner);
        assertThat(field(viewers, "$.data.viewCount")).isEqualTo("1");
        assertThat(field(viewers, "$.data.viewers[0].user.id")).isEqualTo(viewer.id().toString());
        assertThat(field(viewers, "$.data.viewers[0].user.displayName")).isEqualTo("Unknown user");
        USERS.unblock(viewer.id(), owner.id());
    }

    @Test
    void onlyTheOwnerSeesTheViewersAndViewingMyOwnStatusRecordsNothing() throws Exception {
        Person owner = newPerson("Owner");
        Person viewer = newPerson("Viewer");
        contact(owner, viewer);
        UUID statusId = textStatus(owner, "Hi");

        assertStatus(get("/api/statuses/" + statusId + "/viewers", viewer), 404);
        assertStatus(get("/api/statuses/" + UUID.randomUUID() + "/viewers", owner), 404);

        view(owner, statusId);
        assertThat(rows("SELECT COUNT(*) FROM status_views WHERE status_id = ?", statusId.toString())).isZero();
    }

    @Test
    void myStatusesAreOnlyMineAndOnlyActiveOnes() throws Exception {
        Person owner = newPerson("Owner");
        Person other = newPerson("Other");
        UUID active = textStatus(owner, "active");
        expire(textStatus(owner, "expired"));
        textStatus(other, "not mine");

        List<String> ids = read(get("/api/statuses/mine", owner), "$.data[*].id");
        assertThat(ids).containsExactly(active.toString());
    }
}
