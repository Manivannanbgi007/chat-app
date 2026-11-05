package com.chatapp.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.chatapp.user.entity.User;
import com.chatapp.user.entity.UserPrivacySettings;
import com.chatapp.user.enums.AccountStatus;
import com.chatapp.user.enums.Visibility;
import com.chatapp.user.repository.BlockedUserRepository;
import com.chatapp.user.repository.ContactRepository;
import com.chatapp.user.repository.PrivacyRepository;
import com.chatapp.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The "who may add whom to a group" rules, with the repositories mocked. */
@ExtendWith(MockitoExtension.class)
class GroupAddPolicyServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PrivacyRepository privacyRepository;
    @Mock
    private ContactRepository contactRepository;
    @Mock
    private BlockedUserRepository blockedUserRepository;

    @InjectMocks
    private GroupAddPolicyService service;

    private final UUID actor = UUID.randomUUID();
    private final List<User> users = new ArrayList<>();
    private final List<UserPrivacySettings> privacy = new ArrayList<>();
    private final List<UUID> haveActorAsContact = new ArrayList<>();
    private final List<UUID> whoBlockedActor = new ArrayList<>();
    private final List<UUID> blockedByActor = new ArrayList<>();

    @BeforeEach
    void wireRepositories() {
        lenient().when(userRepository.findAllById(any())).thenReturn(users);
        lenient().when(privacyRepository.findAllById(any())).thenReturn(privacy);
        lenient().when(contactRepository.findOwnersWhoHaveContact(eq(actor), any())).thenReturn(haveActorAsContact);
        lenient().when(blockedUserRepository.findUsersWhoBlocked(eq(actor), any())).thenReturn(whoBlockedActor);
        lenient().when(blockedUserRepository.findUsersBlockedBy(eq(actor), any())).thenReturn(blockedByActor);
    }

    @Test
    void everyoneSettingAllowsAnybody() {
        UUID bob = user(Visibility.EVERYONE);

        assertThat(service.notAllowed(actor, List.of(bob))).isEmpty();
    }

    @Test
    void missingPrivacyRowMeansEveryone() {
        UUID bob = UUID.randomUUID();
        users.add(activeUser(bob));

        assertThat(service.notAllowed(actor, List.of(bob))).isEmpty();
    }

    @Test
    void contactsSettingNeedsTheActorInTheUsersOwnContactList() {
        UUID friend = user(Visibility.CONTACTS);
        UUID stranger = user(Visibility.CONTACTS);
        haveActorAsContact.add(friend);   // friend saved the actor as a contact, stranger did not

        assertThat(service.notAllowed(actor, List.of(friend, stranger))).containsExactly(stranger);
    }

    @Test
    void nobodySettingRefusesEvenContacts() {
        UUID bob = user(Visibility.NOBODY);
        haveActorAsContact.add(bob);

        assertThat(service.notAllowed(actor, List.of(bob))).containsExactly(bob);
    }

    @Test
    void blocksInEitherDirectionRefuse() {
        UUID blockedTheActor = user(Visibility.EVERYONE);
        UUID blockedByTheActor = user(Visibility.EVERYONE);
        UUID fine = user(Visibility.EVERYONE);
        whoBlockedActor.add(blockedTheActor);
        blockedByActor.add(blockedByTheActor);

        assertThat(service.notAllowed(actor, List.of(blockedTheActor, fine, blockedByTheActor)))
                .containsExactly(blockedTheActor, blockedByTheActor);
    }

    @Test
    void unknownAndSuspendedUsersAreRefused() {
        UUID unknown = UUID.randomUUID();
        UUID suspended = user(Visibility.EVERYONE);
        users.get(users.size() - 1).setAccountStatus(AccountStatus.SUSPENDED);

        assertThat(service.notAllowed(actor, List.of(unknown, suspended))).containsExactly(unknown, suspended);
    }

    @Test
    void answerKeepsTheRequestOrderWithoutDuplicates() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertThat(service.notAllowed(actor, List.of(second, first, second))).containsExactly(second, first);
    }

    @Test
    void theActorThemselvesIsAllowedAndNeedsNoQuery() {
        assertThat(service.notAllowed(actor, List.of(actor))).isEmpty();
        verifyNoInteractions(userRepository, privacyRepository, contactRepository, blockedUserRepository);
    }

    // ---- helpers ----

    private UUID user(Visibility groupAdd) {
        UUID id = UUID.randomUUID();
        users.add(activeUser(id));
        UserPrivacySettings settings = UserPrivacySettings.defaultsFor(id);
        settings.setGroupAdd(groupAdd);
        privacy.add(settings);
        return id;
    }

    private static User activeUser(UUID id) {
        User user = new User();
        user.setId(id);
        user.setAccountStatus(AccountStatus.ACTIVE);
        return user;
    }
}
