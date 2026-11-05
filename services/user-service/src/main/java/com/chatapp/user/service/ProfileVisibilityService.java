package com.chatapp.user.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.chatapp.user.dto.response.PublicProfileResponse;
import com.chatapp.user.entity.UserPrivacySettings;
import com.chatapp.user.enums.Visibility;
import com.chatapp.user.repository.BlockedUserRepository;
import com.chatapp.user.repository.ContactRepository;
import com.chatapp.user.repository.PrivacyRepository;
import com.chatapp.user.repository.UserWithProfile;
import org.springframework.stereotype.Service;

/**
 * The privacy rules in one place: decides what a viewer may see of other users.
 * Works on a whole list at once (a few queries instead of a few queries per user).
 */
@Service
public class ProfileVisibilityService {

    private final PrivacyRepository privacyRepository;
    private final ContactRepository contactRepository;
    private final BlockedUserRepository blockedUserRepository;

    public ProfileVisibilityService(PrivacyRepository privacyRepository,
                                    ContactRepository contactRepository,
                                    BlockedUserRepository blockedUserRepository) {
        this.privacyRepository = privacyRepository;
        this.contactRepository = contactRepository;
        this.blockedUserRepository = blockedUserRepository;
    }

    /**
     * Builds the public profile of every target as the viewer is allowed to see it.
     * Rules: you always see yourself fully; someone who blocked you shows nothing private;
     * otherwise each field follows that user's privacy setting.
     */
    public List<PublicProfileResponse> buildPublicProfiles(UUID viewerId, List<UserWithProfile> targets) {
        if (targets.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = targets.stream().map(t -> t.user().getId()).collect(Collectors.toSet());

        Map<UUID, UserPrivacySettings> privacyByUser = privacyRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(UserPrivacySettings::getUserId, Function.identity()));
        Set<UUID> ownersWhoHaveViewerAsContact = new HashSet<>(contactRepository.findOwnersWhoHaveContact(viewerId, ids));
        Set<UUID> usersWhoBlockedViewer = new HashSet<>(blockedUserRepository.findUsersWhoBlocked(viewerId, ids));

        return targets.stream().map(target -> {
            UUID id = target.user().getId();
            boolean self = id.equals(viewerId);
            UserPrivacySettings privacy = privacyByUser.get(id);
            boolean viewerIsContact = ownersWhoHaveViewerAsContact.contains(id);

            boolean showPhoto = self || (!usersWhoBlockedViewer.contains(id)
                    && allows(privacy == null ? Visibility.EVERYONE : privacy.getProfilePhoto(), viewerIsContact));
            boolean showAbout = self || (!usersWhoBlockedViewer.contains(id)
                    && allows(privacy == null ? Visibility.EVERYONE : privacy.getAbout(), viewerIsContact));

            return new PublicProfileResponse(
                    id,
                    target.user().getUsername(),
                    target.profile().getDisplayName(),
                    showPhoto ? target.profile().getProfileImageUrl() : null,
                    showAbout ? target.profile().getAbout() : null);
        }).toList();
    }

    /** True if a field with this setting may be shown to a viewer (viewerIsContact = owner has viewer as contact). */
    public static boolean allows(Visibility visibility, boolean viewerIsContact) {
        return switch (visibility) {
            case EVERYONE -> true;
            case CONTACTS -> viewerIsContact;
            case NOBODY -> false;
        };
    }
}
