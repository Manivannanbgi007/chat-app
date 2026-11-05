package com.chatapp.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chatapp.notification.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** conversation.events in, "added / removed / admin" notifications out. */
class GroupNotificationTest extends AbstractIntegrationTest {

    private static final String TOPIC = "conversation.events";

    private UUID creator;
    private UUID member;
    private UUID other;
    private UUID group;

    @BeforeEach
    void setUp() {
        creator = newId();
        member = newId();
        other = newId();
        group = newId();
        PEERS.addGroup(group, "Trip to Goa");
        PEERS.conversationDown = false;
    }

    @Test
    void creatingAGroupNotifiesEveryoneExceptTheCreator() {
        UUID eventId = groupCreated(group, creator, "Asha", "Trip to Goa", List.of(creator, member, other));
        awaitHandled(eventId);

        assertThat(notificationCount(creator)).isZero();
        for (UUID user : List.of(member, other)) {
            List<Map<String, Object>> rows = notificationsOf(user);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).containsEntry("type", "GROUP_ADDED").containsEntry("title", "Trip to Goa")
                    .containsEntry("body", "Asha added you");
        }
    }

    @Test
    void aPrivateConversationNotifiesNobody() {
        UUID eventId = newId();
        UUID chat = newId();
        publish(TOPIC, chat, event(eventId, "ConversationCreatedEvent", chat, Map.of(
                "conversationId", chat.toString(), "type", "PRIVATE", "createdBy", creator.toString(),
                "participantIds", List.of(creator.toString(), member.toString()))));
        flush(TOPIC, chat);
        assertThat(notificationCount(member)).isZero();
    }

    @Test
    void addedMembersAreNotifiedButNotTheActor() {
        UUID eventId = membersAdded(group, creator, "Asha", List.of(member, creator));
        awaitHandled(eventId);

        assertThat(notificationCount(creator)).isZero();
        assertThat(notificationsOf(member)).hasSize(1);
        assertThat(notificationsOf(member).get(0)).containsEntry("type", "GROUP_ADDED")
                .containsEntry("title", "Trip to Goa").containsEntry("body", "Asha added you");
    }

    @Test
    void removedMemberIsNotifiedButSomebodyWhoLeftIsNot() {
        awaitHandled(memberRemoved(group, member, "REMOVED", "Asha"));
        memberRemoved(group, other, "LEFT", null);
        flush(TOPIC, group);

        assertThat(notificationsOf(member)).hasSize(1);
        assertThat(notificationsOf(member).get(0)).containsEntry("type", "GROUP_REMOVED")
                .containsEntry("body", "Asha removed you").containsEntry("title", "Trip to Goa");
        assertThat(notificationCount(other)).isZero();
    }

    @Test
    void becomingAdminNotifiesButBecomingMemberDoesNot() {
        awaitHandled(roleChanged(group, member, "ADMIN"));
        roleChanged(group, other, "MEMBER");
        flush(TOPIC, group);

        assertThat(notificationsOf(member)).hasSize(1);
        assertThat(notificationsOf(member).get(0)).containsEntry("type", "GROUP_ADMIN")
                .containsEntry("body", "You are now an admin");
        assertThat(notificationCount(other)).isZero();
    }

    @Test
    void groupNotificationsAreNotAggregated() {
        awaitHandled(membersAdded(group, creator, "Asha", List.of(member)));
        awaitHandled(roleChanged(group, member, "ADMIN"));
        assertThat(notificationsOf(member)).hasSize(2);
    }

    @Test
    void groupsEnabledFalseSkipsEverything() throws Exception {
        setPreferences(member, true, true, false);
        awaitHandled(membersAdded(group, creator, "Asha", List.of(member)));
        roleChanged(group, member, "ADMIN");
        flush(TOPIC, group);
        assertThat(notificationCount(member)).isZero();
    }

    @Test
    void onlineUsersAreStillNotified() {
        PEERS.setOnline(member);
        try {
            awaitHandled(membersAdded(group, creator, "Asha", List.of(member)));
            assertThat(notificationsOf(member)).hasSize(1);
        } finally {
            PEERS.setOffline(member);
        }
    }

    @Test
    void theSameEventTwiceCreatesOneNotification() {
        UUID eventId = newId();
        String json = event(eventId, "GroupRoleChangedEvent", group, Map.of(
                "conversationId", group.toString(), "userId", member.toString(), "role", "ADMIN"));
        publish(TOPIC, group, json);
        publish(TOPIC, group, json);
        flush(TOPIC, group);
        assertThat(notificationsOf(member)).hasSize(1);
    }

    @Test
    void whenTheEventHasNoNamesTheTextsFallBack() {
        UUID unknownGroup = newId();       // conversation-service does not know it, the event has no group name
        awaitHandled(membersAdded(unknownGroup, creator, null, List.of(member)));
        assertThat(notificationsOf(member).get(0)).containsEntry("title", "Group chat").containsEntry("body", "Someone added you");
    }

    @Test
    void groupNotificationsAreAlsoPublishedLive() throws Exception {
        try (LiveListener live = new LiveListener()) {
            awaitHandled(roleChanged(group, member, "ADMIN"));
            eventually(() -> !live.forUser(member).isEmpty(), "live event");
            assertThat(live.forUser(member).get(0)).contains("GROUP_ADMIN");
        }
    }
}
