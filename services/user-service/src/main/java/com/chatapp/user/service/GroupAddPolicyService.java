package com.chatapp.user.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.chatapp.user.entity.User;
import com.chatapp.user.entity.UserPrivacySettings;
import com.chatapp.user.enums.AccountStatus;
import com.chatapp.user.enums.Visibility;
import com.chatapp.user.repository.BlockedUserRepository;
import com.chatapp.user.repository.ContactRepository;
import com.chatapp.user.repository.PrivacyRepository;
import com.chatapp.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides who may add whom to a group (asked by conversation-service before it creates a group or adds members).
 *
 * A user can NOT be added by the actor when:
 *  - the user does not exist (or the account is not active),
 *  - either of the two blocked the other,
 *  - the user's "groupAdd" privacy setting refuses the actor:
 *      EVERYONE -> anybody may add them
 *      CONTACTS -> only people in THEIR OWN contact list (the user saved the actor as a contact)
 *      NOBODY   -> nobody may add them
 * The caller gets one list for all three reasons, so it cannot tell which reason applied.
 * Works on the whole list with a handful of queries (no query per user).
 */
@Service
public class GroupAddPolicyService {

    private final UserRepository userRepository;
    private final PrivacyRepository privacyRepository;
    private final ContactRepository contactRepository;
    private final BlockedUserRepository blockedUserRepository;

    public GroupAddPolicyService(UserRepository userRepository, PrivacyRepository privacyRepository,
                                 ContactRepository contactRepository, BlockedUserRepository blockedUserRepository) {
        this.userRepository = userRepository;
        this.privacyRepository = privacyRepository;
        this.contactRepository = contactRepository;
        this.blockedUserRepository = blockedUserRepository;
    }

    /** The users (in request order, without duplicates) that the actor may NOT add to a group. */
    @Transactional(readOnly = true)
    public List<UUID> notAllowed(UUID actorId, List<UUID> userIds) {
        Set<UUID> wanted = new LinkedHashSet<>(userIds);
        wanted.remove(actorId);            // the actor "adding" themselves is always fine
        if (wanted.isEmpty()) {
            return List.of();
        }

        Set<UUID> activeUsers = userRepository.findAllById(wanted).stream()
                .filter(user -> user.getAccountStatus() == AccountStatus.ACTIVE)
                .map(User::getId)
                .collect(Collectors.toSet());
        Map<UUID, UserPrivacySettings> privacyByUser = privacyRepository.findAllById(wanted).stream()
                .collect(Collectors.toMap(UserPrivacySettings::getUserId, Function.identity()));
        // users who saved the actor in their own contact list (needed for the CONTACTS setting)
        Set<UUID> haveActorAsContact = new HashSet<>(contactRepository.findOwnersWhoHaveContact(actorId, wanted));
        Set<UUID> blockedEitherWay = new HashSet<>(blockedUserRepository.findUsersWhoBlocked(actorId, wanted));
        blockedEitherWay.addAll(blockedUserRepository.findUsersBlockedBy(actorId, wanted));

        List<UUID> refused = new ArrayList<>();
        for (UUID userId : wanted) {
            UserPrivacySettings privacy = privacyByUser.get(userId);
            Visibility groupAdd = privacy == null ? Visibility.EVERYONE : privacy.getGroupAdd();
            boolean allowed = activeUsers.contains(userId)
                    && !blockedEitherWay.contains(userId)
                    && ProfileVisibilityService.allows(groupAdd, haveActorAsContact.contains(userId));
            if (!allowed) {
                refused.add(userId);
            }
        }
        return refused;
    }
}
