package com.chatapp.user.service;

import java.util.UUID;

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
 * Decides whether a viewer may see the online status / last seen of another user.
 * Asked by presence-service, which never sees privacy settings, contacts or blocks itself.
 *
 * Rules, in this order (the first "no" wins):
 *  1. A user always sees themselves.
 *  2. The target must exist and be active.
 *  3. Nobody sees a user who blocked them, or whom they blocked.
 *  4. Reciprocity (as in WhatsApp): a viewer who hides their OWN last seen (NOBODY) cannot see anybody's.
 *  5. The target's "lastSeen" setting: EVERYONE -> yes, CONTACTS -> only if the TARGET has the viewer in the
 *     target's own contact list (the direction matters), NOBODY -> no.
 * A user without a privacy row is treated as having the defaults (EVERYONE).
 */
@Service
public class PresenceVisibilityService {

    private final UserRepository userRepository;
    private final PrivacyRepository privacyRepository;
    private final ContactRepository contactRepository;
    private final BlockedUserRepository blockedUserRepository;

    public PresenceVisibilityService(UserRepository userRepository, PrivacyRepository privacyRepository,
                                     ContactRepository contactRepository, BlockedUserRepository blockedUserRepository) {
        this.userRepository = userRepository;
        this.privacyRepository = privacyRepository;
        this.contactRepository = contactRepository;
        this.blockedUserRepository = blockedUserRepository;
    }

    @Transactional(readOnly = true)
    public boolean canSee(UUID viewerId, UUID targetId) {
        if (viewerId.equals(targetId)) {
            return true;
        }
        boolean targetIsActive = userRepository.findById(targetId)
                .map(User::getAccountStatus)
                .map(status -> status == AccountStatus.ACTIVE)
                .orElse(false);
        if (!targetIsActive) {
            return false;
        }
        if (blockedUserRepository.existsByBlockerIdAndBlockedId(viewerId, targetId)
                || blockedUserRepository.existsByBlockerIdAndBlockedId(targetId, viewerId)) {
            return false;
        }
        if (lastSeenSetting(viewerId) == Visibility.NOBODY) {
            return false;
        }
        Visibility targetSetting = lastSeenSetting(targetId);
        boolean targetHasViewerAsContact = targetSetting == Visibility.CONTACTS
                && contactRepository.existsByOwnerIdAndContactUserId(targetId, viewerId);
        return ProfileVisibilityService.allows(targetSetting, targetHasViewerAsContact);
    }

    private Visibility lastSeenSetting(UUID userId) {
        return privacyRepository.findById(userId)
                .map(UserPrivacySettings::getLastSeen)
                .orElse(Visibility.EVERYONE);
    }
}
