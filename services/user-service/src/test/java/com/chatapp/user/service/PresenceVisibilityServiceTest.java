package com.chatapp.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import com.chatapp.user.entity.User;
import com.chatapp.user.entity.UserPrivacySettings;
import com.chatapp.user.enums.AccountStatus;
import com.chatapp.user.enums.Visibility;
import com.chatapp.user.repository.BlockedUserRepository;
import com.chatapp.user.repository.ContactRepository;
import com.chatapp.user.repository.PrivacyRepository;
import com.chatapp.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** "May the viewer see the target's online status?" with the repositories mocked. */
@ExtendWith(MockitoExtension.class)
class PresenceVisibilityServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PrivacyRepository privacyRepository;
    @Mock
    private ContactRepository contactRepository;
    @Mock
    private BlockedUserRepository blockedUserRepository;

    @InjectMocks
    private PresenceVisibilityService service;

    private final UUID viewer = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    private void activeTarget() {
        User user = new User();
        user.setId(target);
        user.setAccountStatus(AccountStatus.ACTIVE);
        lenient().when(userRepository.findById(target)).thenReturn(Optional.of(user));
    }

    private void lastSeen(UUID userId, Visibility visibility) {
        UserPrivacySettings settings = UserPrivacySettings.defaultsFor(userId);
        settings.setLastSeen(visibility);
        lenient().when(privacyRepository.findById(userId)).thenReturn(Optional.of(settings));
    }

    private void targetHasViewerAsContact(boolean yes) {
        lenient().when(contactRepository.existsByOwnerIdAndContactUserId(target, viewer)).thenReturn(yes);
    }

    @Test
    void aUserAlwaysSeesThemselves() {
        // nothing is stubbed: no repository is needed
        assertThat(service.canSee(viewer, viewer)).isTrue();
    }

    @Test
    void anUnknownTargetIsNotVisible() {
        lenient().when(userRepository.findById(target)).thenReturn(Optional.empty());
        assertThat(service.canSee(viewer, target)).isFalse();
    }

    @Test
    void aSuspendedTargetIsNotVisible() {
        User user = new User();
        user.setId(target);
        user.setAccountStatus(AccountStatus.SUSPENDED);
        lenient().when(userRepository.findById(target)).thenReturn(Optional.of(user));
        assertThat(service.canSee(viewer, target)).isFalse();
    }

    @Test
    void aBlockInEitherDirectionHidesTheStatus() {
        activeTarget();
        lenient().when(blockedUserRepository.existsByBlockerIdAndBlockedId(viewer, target)).thenReturn(true);
        assertThat(service.canSee(viewer, target)).as("viewer blocked target").isFalse();

        lenient().when(blockedUserRepository.existsByBlockerIdAndBlockedId(viewer, target)).thenReturn(false);
        lenient().when(blockedUserRepository.existsByBlockerIdAndBlockedId(target, viewer)).thenReturn(true);
        assertThat(service.canSee(viewer, target)).as("target blocked viewer").isFalse();
    }

    @Test
    void missingPrivacyRowsMeanEveryone() {
        activeTarget();
        assertThat(service.canSee(viewer, target)).isTrue();
    }

    /** viewer's own setting, target's setting, does the target have the viewer as a contact, expected answer */
    static Stream<Arguments> matrix() {
        Visibility e = Visibility.EVERYONE;
        Visibility c = Visibility.CONTACTS;
        Visibility n = Visibility.NOBODY;
        return Stream.of(
                // target EVERYONE
                Arguments.of(e, e, false, true),
                Arguments.of(c, e, false, true),
                Arguments.of(n, e, false, false),   // reciprocity: the viewer hides their own status
                // target CONTACTS: only when the TARGET saved the viewer
                Arguments.of(e, c, true, true),
                Arguments.of(e, c, false, false),
                Arguments.of(c, c, true, true),
                Arguments.of(n, c, true, false),    // reciprocity wins over the contact
                // target NOBODY
                Arguments.of(e, n, true, false),
                Arguments.of(e, n, false, false),
                Arguments.of(c, n, true, false),
                Arguments.of(n, n, false, false));
    }

    @ParameterizedTest(name = "viewer {0}, target {1}, target has viewer as contact {2} -> visible {3}")
    @MethodSource("matrix")
    void privacyMatrix(Visibility viewerSetting, Visibility targetSetting, boolean contact, boolean expected) {
        activeTarget();
        lastSeen(viewer, viewerSetting);
        lastSeen(target, targetSetting);
        targetHasViewerAsContact(contact);

        assertThat(service.canSee(viewer, target)).isEqualTo(expected);
    }

    @Test
    void theContactDirectionMatters() {
        activeTarget();
        lastSeen(target, Visibility.CONTACTS);
        // the VIEWER saved the target, but the target did not save the viewer
        lenient().when(contactRepository.existsByOwnerIdAndContactUserId(viewer, target)).thenReturn(true);
        lenient().when(contactRepository.existsByOwnerIdAndContactUserId(target, viewer)).thenReturn(false);

        assertThat(service.canSee(viewer, target)).isFalse();
    }
}
