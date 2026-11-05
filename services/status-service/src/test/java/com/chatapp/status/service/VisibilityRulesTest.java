package com.chatapp.status.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.chatapp.status.enums.PrivacyMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Every branch of "may this viewer see this owner's statuses?". */
class VisibilityRulesTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID VIEWER = UUID.randomUUID();

    @ParameterizedTest
    @EnumSource(PrivacyMode.class)
    void theOwnerAlwaysSeesTheirOwnStatusesWhateverTheSettings(PrivacyMode mode) {
        assertThat(VisibilityRules.maySee(OWNER, OWNER, false, true, mode, false)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(PrivacyMode.class)
    void aBlockInEitherDirectionHidesEverything(PrivacyMode mode) {
        assertThat(VisibilityRules.maySee(VIEWER, OWNER, true, true, mode, true)).isFalse();
        assertThat(VisibilityRules.maySee(VIEWER, OWNER, true, true, mode, false)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(PrivacyMode.class)
    void someoneWhoIsNotInTheOwnersContactsNeverSeesAnything(PrivacyMode mode) {
        assertThat(VisibilityRules.maySee(VIEWER, OWNER, false, false, mode, true)).isFalse();
        assertThat(VisibilityRules.maySee(VIEWER, OWNER, false, false, mode, false)).isFalse();
    }

    @Test
    void contactsModeShowsEveryContact() {
        assertThat(VisibilityRules.maySee(VIEWER, OWNER, true, false, PrivacyMode.CONTACTS, false)).isTrue();
    }

    @Test
    void noSettingMeansContacts() {
        assertThat(VisibilityRules.maySee(VIEWER, OWNER, true, false, null, false)).isTrue();
    }

    @Test
    void contactsExceptHidesTheListedPeopleOnly() {
        assertThat(VisibilityRules.maySee(VIEWER, OWNER, true, false, PrivacyMode.CONTACTS_EXCEPT, true)).isFalse();
        assertThat(VisibilityRules.maySee(VIEWER, OWNER, true, false, PrivacyMode.CONTACTS_EXCEPT, false)).isTrue();
    }

    @Test
    void onlyShareWithShowsTheListedContactsOnly() {
        assertThat(VisibilityRules.maySee(VIEWER, OWNER, true, false, PrivacyMode.ONLY_SHARE_WITH, true)).isTrue();
        assertThat(VisibilityRules.maySee(VIEWER, OWNER, true, false, PrivacyMode.ONLY_SHARE_WITH, false)).isFalse();
        // listed, but no longer a contact: hidden
        assertThat(VisibilityRules.maySee(VIEWER, OWNER, false, false, PrivacyMode.ONLY_SHARE_WITH, true)).isFalse();
    }
}
