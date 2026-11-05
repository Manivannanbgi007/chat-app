package com.chatapp.status.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chatapp.status.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Who sees whose statuses (contacts, blocks, the three privacy modes) on every way to reach a status:
 * the feed, viewing it, and its media link. Plus the shape and order of the feed.
 */
class VisibilityAndFeedTest extends AbstractIntegrationTest {

    private List<String> feedOwnerIds(Person viewer) throws Exception {
        MvcResult feed = get("/api/statuses/feed", viewer);
        assertStatus(feed, 200);
        return read(feed, "$.data[*].owner.id");
    }

    private boolean canView(Person viewer, UUID statusId) throws Exception {
        int status = post("/api/statuses/" + statusId + "/view", null, viewer).getResponse().getStatus();
        assertThat(status).isIn(200, 404);
        return status == 200;
    }

    private void setPrivacy(Person owner, String mode, Person... people) throws Exception {
        List<UUID> ids = java.util.Arrays.stream(people).map(Person::id).toList();
        assertStatus(put("/api/statuses/privacy", Map.of("mode", mode, "userIds", ids), owner), 200);
    }

    // ------------------------------------------------------------ contacts and blocks

    @Test
    void onlyPeopleInTheOwnersContactListSeeTheStatus() throws Exception {
        Person owner = newPerson("Owner");
        Person contact = newPerson("Contact");
        Person stranger = newPerson("Stranger");
        Person oneWay = newPerson("OneWay");
        contact(owner, contact);
        contact(oneWay, owner);      // oneWay has the OWNER as contact, not the other way round: no access
        UUID statusId = textStatus(owner, "Hello contacts");

        assertThat(feedOwnerIds(contact)).contains(owner.id().toString());
        assertThat(canView(contact, statusId)).isTrue();

        assertThat(feedOwnerIds(stranger)).doesNotContain(owner.id().toString());
        assertThat(canView(stranger, statusId)).isFalse();
        assertThat(feedOwnerIds(oneWay)).doesNotContain(owner.id().toString());
        assertThat(canView(oneWay, statusId)).isFalse();
    }

    @Test
    void aBlockInEitherDirectionHidesTheStatusAtOnce() throws Exception {
        Person owner = newPerson("Owner");
        Person viewer = newPerson("Viewer");
        contact(owner, viewer);
        UUID statusId = textStatus(owner, "Before the block");
        assertThat(canView(viewer, statusId)).isTrue();

        USERS.block(owner.id(), viewer.id());
        assertThat(feedOwnerIds(viewer)).doesNotContain(owner.id().toString());
        assertThat(canView(viewer, statusId)).isFalse();
        USERS.unblock(owner.id(), viewer.id());

        USERS.block(viewer.id(), owner.id());
        assertThat(feedOwnerIds(viewer)).doesNotContain(owner.id().toString());
        assertThat(canView(viewer, statusId)).isFalse();
        USERS.unblock(viewer.id(), owner.id());

        assertThat(canView(viewer, statusId)).isTrue();
    }

    @Test
    void removingTheContactHidesStatusesThatWerePostedBefore() throws Exception {
        Person owner = newPerson("Owner");
        Person viewer = newPerson("Viewer");
        contact(owner, viewer);
        UUID statusId = textStatus(owner, "Old");

        USERS.removeContact(owner.id(), viewer.id());

        assertThat(feedOwnerIds(viewer)).doesNotContain(owner.id().toString());
        assertThat(canView(viewer, statusId)).isFalse();
    }

    // ------------------------------------------------------------ privacy modes

    @Test
    void contactsExceptHidesTheExceptedPeopleOnly() throws Exception {
        Person owner = newPerson("Owner");
        Person kept = newPerson("Kept");
        Person excepted = newPerson("Excepted");
        contact(owner, kept);
        contact(owner, excepted);
        UUID statusId = textStatus(owner, "Not for everyone");

        setPrivacy(owner, "CONTACTS_EXCEPT", excepted);

        assertThat(feedOwnerIds(kept)).contains(owner.id().toString());
        assertThat(canView(kept, statusId)).isTrue();
        assertThat(feedOwnerIds(excepted)).doesNotContain(owner.id().toString());
        assertThat(canView(excepted, statusId)).isFalse();

        // back to all contacts: the change applies to the old status too
        setPrivacy(owner, "CONTACTS");
        assertThat(canView(excepted, statusId)).isTrue();
    }

    @Test
    void onlyShareWithShowsTheChosenContactsAndNobodyElse() throws Exception {
        Person owner = newPerson("Owner");
        Person chosen = newPerson("Chosen");
        Person otherContact = newPerson("OtherContact");
        Person chosenButNotContact = newPerson("ChosenNotContact");
        contact(owner, chosen);
        contact(owner, otherContact);
        UUID statusId = textStatus(owner, "Close friends");

        setPrivacy(owner, "ONLY_SHARE_WITH", chosen, chosenButNotContact);

        assertThat(feedOwnerIds(chosen)).contains(owner.id().toString());
        assertThat(canView(chosen, statusId)).isTrue();
        assertThat(feedOwnerIds(otherContact)).doesNotContain(owner.id().toString());
        assertThat(canView(otherContact, statusId)).isFalse();
        // on the list, but not in the owner's contacts: still nothing
        assertThat(feedOwnerIds(chosenButNotContact)).doesNotContain(owner.id().toString());
        assertThat(canView(chosenButNotContact, statusId)).isFalse();
    }

    @Test
    void mediaLinksFollowTheSameRules() throws Exception {
        Person owner = newPerson("Owner");
        Person viewer = newPerson("Viewer");
        Person stranger = newPerson("Stranger");
        contact(owner, viewer);
        UUID[] photo = photoStatus(owner, true);

        MvcResult original = get("/api/statuses/" + photo[0] + "/media-url", viewer);
        assertStatus(original, 200);
        assertThat(field(original, "$.data.url")).contains(photo[1].toString()).contains("/original")
                .contains("for=" + viewer.id());
        assertThat(field(original, "$.data.expiresAt")).isNotNull();
        assertThat(field(get("/api/statuses/" + photo[0] + "/media-url?variant=thumbnail", viewer), "$.data.url"))
                .contains("/thumbnail");
        assertStatus(get("/api/statuses/" + photo[0] + "/media-url", owner), 200);

        assertStatus(get("/api/statuses/" + photo[0] + "/media-url", stranger), 404);

        // a photo without thumbnail, and a text status without any media
        UUID[] noThumbnail = photoStatus(owner, false);
        assertStatus(get("/api/statuses/" + noThumbnail[0] + "/media-url?variant=thumbnail", viewer), 404);
        UUID text = textStatus(owner, "no media");
        assertStatus(get("/api/statuses/" + text + "/media-url", viewer), 404);
        assertStatus(get("/api/statuses/" + UUID.randomUUID() + "/media-url", viewer), 404);
    }

    // ------------------------------------------------------------ the feed itself

    @Test
    void theFeedGroupsByPersonWithUnseenPeopleFirstAndNewestFirst() throws Exception {
        Person me = newPerson("Me");
        Person early = newPerson("Early");
        Person late = newPerson("Late");
        Person seen = newPerson("Seen");
        for (Person owner : List.of(early, late, seen)) {
            contact(owner, me);
        }
        UUID seenStatus = textStatus(seen, "seen 1");
        UUID early1 = textStatus(early, "early 1");
        UUID early2 = textStatus(early, "early 2");
        textStatus(late, "late 1");
        assertThat(canView(me, seenStatus)).isTrue();

        MvcResult feed = get("/api/statuses/feed", me);
        assertStatus(feed, 200);

        // late (newest, unseen), early (unseen), then seen (fully viewed) last even though it is not the oldest
        List<String> owners = read(feed, "$.data[*].owner.id");
        assertThat(owners).containsExactly(late.id().toString(), early.id().toString(), seen.id().toString());
        assertThat(field(feed, "$.data[1].owner.displayName")).isEqualTo(early.name());
        List<String> earlyStatuses = read(feed, "$.data[1].statuses[*].id");
        assertThat(earlyStatuses).containsExactly(early1.toString(), early2.toString());    // oldest first
        assertThat(field(feed, "$.data[1].latestAt")).isEqualTo(field(feed, "$.data[1].statuses[1].createdAt"));
        assertThat(field(feed, "$.data[1].allViewed")).isEqualTo("false");
        assertThat(field(feed, "$.data[2].allViewed")).isEqualTo("true");
        assertThat(field(feed, "$.data[2].statuses[0].viewedByMe")).isEqualTo("true");
        assertThat(field(feed, "$.data[0].statuses[0].viewedByMe")).isEqualTo("false");

        // viewing one of two keeps the group "unseen" but marks that status
        assertThat(canView(me, early1)).isTrue();
        MvcResult after = get("/api/statuses/feed", me);
        assertThat(field(after, "$.data[1].owner.id")).isEqualTo(early.id().toString());
        assertThat(field(after, "$.data[1].statuses[0].viewedByMe")).isEqualTo("true");
        assertThat(field(after, "$.data[1].allViewed")).isEqualTo("false");
    }

    @Test
    void myOwnStatusesAreNeverInMyFeedAndPeopleWithoutActiveStatusesAreLeftOut() throws Exception {
        Person me = newPerson("Me");
        Person quiet = newPerson("Quiet");
        Person expired = newPerson("Expired");
        contact(quiet, me);
        contact(expired, me);
        contact(me, me);
        textStatus(me, "mine");
        expire(textStatus(expired, "gone"));

        assertThat(feedOwnerIds(me)).isEmpty();
    }

    @Test
    void aFeedOwnerUserServiceHasNoProfileForIsLeftOut() throws Exception {
        Person me = newPerson("Me");
        Person owner = newPerson("Owner");
        Person gone = newPerson("Gone");
        contact(owner, me);
        contact(gone, me);
        textStatus(owner, "hi");
        textStatus(gone, "bye");

        USERS.forgetUser(gone.id());

        assertThat(feedOwnerIds(me)).containsExactly(owner.id().toString());
        assertThat(field(get("/api/statuses/feed", me), "$.data[0].owner.username"))
                .isEqualTo(owner.name().toLowerCase().replace(' ', '_'));
    }

    @Test
    void theFeedIs503WhenUserServiceIsDown() throws Exception {
        Person me = newPerson("Me");
        Person owner = newPerson("Owner");
        contact(owner, me);
        UUID statusId = textStatus(owner, "hi");

        USERS.down = true;
        assertError(get("/api/statuses/feed", me), 503, "DEPENDENCY_UNAVAILABLE");
        assertError(post("/api/statuses/" + statusId + "/view", null, me), 503, "DEPENDENCY_UNAVAILABLE");
        // an unknown status is 404 before anybody is asked
        assertStatus(post("/api/statuses/" + UUID.randomUUID() + "/view", null, me), 404);
    }
}
