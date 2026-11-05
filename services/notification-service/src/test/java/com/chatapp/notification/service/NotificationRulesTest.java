package com.chatapp.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.chatapp.notification.entity.NotificationPreference;
import org.junit.jupiter.api.Test;

class NotificationRulesTest {

    private NotificationPreference preference(boolean push, boolean messages, boolean groups) {
        NotificationPreference preference = NotificationPreference.defaultsFor(UUID.randomUUID());
        preference.setPushEnabled(push);
        preference.setMessagesEnabled(messages);
        preference.setGroupsEnabled(groups);
        return preference;
    }

    @Test
    void privateMessagesFollowMessagesEnabledAndGroupMessagesFollowGroupsEnabled() {
        assertThat(NotificationRules.wantsMessageNotification(preference(true, true, false), false)).isTrue();
        assertThat(NotificationRules.wantsMessageNotification(preference(true, true, false), true)).isFalse();
        assertThat(NotificationRules.wantsMessageNotification(preference(true, false, true), false)).isFalse();
        assertThat(NotificationRules.wantsMessageNotification(preference(true, false, true), true)).isTrue();
    }

    @Test
    void groupChangesFollowGroupsEnabled() {
        assertThat(NotificationRules.wantsGroupNotification(preference(true, true, true))).isTrue();
        assertThat(NotificationRules.wantsGroupNotification(preference(true, true, false))).isFalse();
    }

    @Test
    void missedCallsFollowCallsEnabledWhichIsOnByDefault() {
        NotificationPreference preference = NotificationPreference.defaultsFor(UUID.randomUUID());
        assertThat(NotificationRules.wantsCallNotification(preference)).isTrue();
        preference.setCallsEnabled(false);
        assertThat(NotificationRules.wantsCallNotification(preference)).isFalse();
    }

    @Test
    void missedCallTextsSayVoiceOrVideo() {
        assertThat(NotificationRules.missedCallBody(false)).isEqualTo("Missed voice call");
        assertThat(NotificationRules.missedCallBody(true)).isEqualTo("Missed video call");
    }

    @Test
    void aPushNeedsPushEnabledATokenAndAnOfflineUser() {
        assertThat(NotificationRules.wantsPush(preference(true, true, true), true, false)).isTrue();
        assertThat(NotificationRules.wantsPush(preference(false, true, true), true, false)).isFalse();
        assertThat(NotificationRules.wantsPush(preference(true, true, true), false, false)).isFalse();
        assertThat(NotificationRules.wantsPush(preference(true, true, true), true, true)).isFalse();
    }

    @Test
    void newUsersHaveEverythingOn() {
        NotificationPreference defaults = NotificationPreference.defaultsFor(UUID.randomUUID());
        assertThat(defaults.isPushEnabled()).isTrue();
        assertThat(defaults.isMessagesEnabled()).isTrue();
        assertThat(defaults.isGroupsEnabled()).isTrue();
        assertThat(defaults.isCallsEnabled()).isTrue();
    }

    @Test
    void messageTitleIsThePersonForPrivateChatsAndTheGroupNameForGroups() {
        assertThat(NotificationRules.messageTitle(false, "Alice", "ignored")).isEqualTo("Alice");
        assertThat(NotificationRules.messageTitle(true, "ignored", "Trip to Goa")).isEqualTo("Trip to Goa");
    }

    @Test
    void missingNamesFallBack() {
        assertThat(NotificationRules.messageTitle(false, null, null)).isEqualTo("Someone");
        assertThat(NotificationRules.messageTitle(false, "  ", null)).isEqualTo("Someone");
        assertThat(NotificationRules.messageTitle(true, null, null)).isEqualTo("Group chat");
        assertThat(NotificationRules.messageTitle(true, "Alice", "")).isEqualTo("Group chat");
        assertThat(NotificationRules.groupTitle(null)).isEqualTo("Group chat");
        assertThat(NotificationRules.groupTitle("Trip")).isEqualTo("Trip");
    }

    @Test
    void groupTextsSayWhoAndWhatButNeverMore() {
        assertThat(NotificationRules.addedBody("Asha")).isEqualTo("Asha added you");
        assertThat(NotificationRules.addedBody(null)).isEqualTo("Someone added you");
        assertThat(NotificationRules.removedBody("Asha")).isEqualTo("Asha removed you");
        assertThat(NotificationRules.removedBody(" ")).isEqualTo("Someone removed you");
        assertThat(NotificationRules.adminBody()).isEqualTo("You are now an admin");
    }
}
