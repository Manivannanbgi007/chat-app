package com.chatapp.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.chatapp.user.dto.response.PublicProfileResponse;
import com.chatapp.user.entity.User;
import com.chatapp.user.entity.UserPrivacySettings;
import com.chatapp.user.entity.UserProfile;
import com.chatapp.user.enums.Visibility;
import com.chatapp.user.repository.BlockedUserRepository;
import com.chatapp.user.repository.ContactRepository;
import com.chatapp.user.repository.PrivacyRepository;
import com.chatapp.user.repository.UserWithProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProfileVisibilityServiceTest {

    @Mock
    private PrivacyRepository privacyRepository;
    @Mock
    private ContactRepository contactRepository;
    @Mock
    private BlockedUserRepository blockedUserRepository;

    @InjectMocks
    private ProfileVisibilityService service;

    private final UUID viewer = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();

    @Test
    void allowsFollowsTheThreeSettings() {
        assertThat(ProfileVisibilityService.allows(Visibility.EVERYONE, false)).isTrue();
        assertThat(ProfileVisibilityService.allows(Visibility.CONTACTS, true)).isTrue();
        assertThat(ProfileVisibilityService.allows(Visibility.CONTACTS, false)).isFalse();
        assertThat(ProfileVisibilityService.allows(Visibility.NOBODY, true)).isFalse();
    }

    @Test
    void everyoneSettingShowsPhotoAndAbout() {
        givenOwnerPrivacy(Visibility.EVERYONE, Visibility.EVERYONE);

        PublicProfileResponse result = build();

        assertThat(result.profileImageUrl()).isEqualTo("http://img/owner.png");
        assertThat(result.about()).isEqualTo("hello");
    }

    @Test
    void contactsSettingHidesFieldsFromStrangers() {
        givenOwnerPrivacy(Visibility.CONTACTS, Visibility.CONTACTS);

        PublicProfileResponse result = build();

        assertThat(result.profileImageUrl()).isNull();
        assertThat(result.about()).isNull();
        // the name is never hidden
        assertThat(result.displayName()).isEqualTo("Owner");
    }

    @Test
    void contactsSettingShowsFieldsWhenOwnerHasViewerAsContact() {
        givenOwnerPrivacy(Visibility.CONTACTS, Visibility.CONTACTS);
        when(contactRepository.findOwnersWhoHaveContact(any(), any())).thenReturn(List.of(owner));

        PublicProfileResponse result = build();

        assertThat(result.profileImageUrl()).isNotNull();
        assertThat(result.about()).isEqualTo("hello");
    }

    @Test
    void nobodySettingHidesFieldsEvenFromContacts() {
        givenOwnerPrivacy(Visibility.NOBODY, Visibility.NOBODY);
        when(contactRepository.findOwnersWhoHaveContact(any(), any())).thenReturn(List.of(owner));

        PublicProfileResponse result = build();

        assertThat(result.profileImageUrl()).isNull();
        assertThat(result.about()).isNull();
    }

    @Test
    void settingsApplyToEachFieldSeparately() {
        givenOwnerPrivacy(Visibility.NOBODY, Visibility.EVERYONE);

        PublicProfileResponse result = build();

        assertThat(result.profileImageUrl()).isNull();
        assertThat(result.about()).isEqualTo("hello");
    }

    @Test
    void someoneWhoBlockedTheViewerShowsNothingPrivate() {
        givenOwnerPrivacy(Visibility.EVERYONE, Visibility.EVERYONE);
        when(blockedUserRepository.findUsersWhoBlocked(any(), any())).thenReturn(List.of(owner));

        PublicProfileResponse result = build();

        assertThat(result.profileImageUrl()).isNull();
        assertThat(result.about()).isNull();
    }

    @Test
    void viewerAlwaysSeesTheirOwnProfileFully() {
        UserPrivacySettings strict = privacy(owner, Visibility.NOBODY, Visibility.NOBODY);
        when(privacyRepository.findAllById(any())).thenReturn(List.of(strict));

        PublicProfileResponse result = service.buildPublicProfiles(owner, List.of(target())).get(0);

        assertThat(result.profileImageUrl()).isNotNull();
        assertThat(result.about()).isEqualTo("hello");
    }

    @Test
    void missingPrivacyRowIsTreatedAsEveryone() {
        when(privacyRepository.findAllById(any())).thenReturn(List.of());

        PublicProfileResponse result = build();

        assertThat(result.about()).isEqualTo("hello");
    }

    @Test
    void emptyListDoesNotTouchTheDatabase() {
        assertThat(service.buildPublicProfiles(viewer, List.of())).isEmpty();
    }

    // ---- helpers ----

    private PublicProfileResponse build() {
        return service.buildPublicProfiles(viewer, List.of(target())).get(0);
    }

    private void givenOwnerPrivacy(Visibility photo, Visibility about) {
        when(privacyRepository.findAllById(any())).thenReturn(List.of(privacy(owner, photo, about)));
    }

    private UserWithProfile target() {
        User user = new User(owner, "owner", "owner@example.com", null);
        UserProfile profile = new UserProfile(owner, "Owner");
        profile.setProfileImageUrl("http://img/owner.png");
        profile.setAbout("hello");
        return new UserWithProfile(user, profile);
    }

    private static UserPrivacySettings privacy(UUID userId, Visibility photo, Visibility about) {
        UserPrivacySettings settings = UserPrivacySettings.defaultsFor(userId);
        settings.setProfilePhoto(photo);
        settings.setAbout(about);
        return settings;
    }
}
