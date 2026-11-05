package com.chatapp.user.service;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.chatapp.user.dto.response.PublicProfileResponse;
import com.chatapp.user.repository.BlockedUserRepository;
import com.chatapp.user.repository.UserWithProfile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Answers questions that other services ask over the internal API (block check, user summaries). */
@Service
public class InternalLookupService {

    private final BlockedUserRepository blockedUserRepository;
    private final UserDirectory userDirectory;
    private final ProfileVisibilityService visibilityService;

    public InternalLookupService(BlockedUserRepository blockedUserRepository,
                                 UserDirectory userDirectory,
                                 ProfileVisibilityService visibilityService) {
        this.blockedUserRepository = blockedUserRepository;
        this.userDirectory = userDirectory;
        this.visibilityService = visibilityService;
    }

    /** True if a blocked b OR b blocked a. */
    @Transactional(readOnly = true)
    public boolean isBlockedEitherWay(UUID a, UUID b) {
        if (a.equals(b)) {
            return false;
        }
        return blockedUserRepository.existsByBlockerIdAndBlockedId(a, b)
                || blockedUserRepository.existsByBlockerIdAndBlockedId(b, a);
    }

    /**
     * The given users as the viewer may see them. Unknown ids are left out, and so are users who
     * blocked the viewer (the same "does not exist" answer the public profile endpoint gives).
     * The answer keeps the order of the request.
     */
    @Transactional(readOnly = true)
    public List<PublicProfileResponse> summaries(UUID viewerId, List<UUID> userIds) {
        Set<UUID> wanted = new LinkedHashSet<>(userIds);
        if (wanted.isEmpty()) {
            return List.of();
        }

        Set<UUID> blockedViewer = Set.copyOf(blockedUserRepository.findUsersWhoBlocked(viewerId, wanted));
        List<UserWithProfile> visible = userDirectory.findAll(wanted).stream()
                .filter(user -> !blockedViewer.contains(user.user().getId()))
                .toList();

        Map<UUID, PublicProfileResponse> byId = new HashMap<>();
        for (PublicProfileResponse profile : visibilityService.buildPublicProfiles(viewerId, visible)) {
            byId.put(profile.id(), profile);
        }
        return wanted.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }
}
