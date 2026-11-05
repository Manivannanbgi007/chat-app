package com.chatapp.status.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.chatapp.status.client.UserClient;
import com.chatapp.status.dto.request.PrivacyRequest;
import com.chatapp.status.dto.response.PrivacyResponse;
import com.chatapp.status.entity.StatusPrivacy;
import com.chatapp.status.entity.StatusPrivacyMember;
import com.chatapp.status.enums.PrivacyMode;
import com.chatapp.status.exception.FieldValidationException;
import com.chatapp.status.repository.StatusPrivacyMemberRepository;
import com.chatapp.status.repository.StatusPrivacyRepository;
import com.chatapp.status.util.Times;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Status privacy: reading and changing MY setting, and deciding whether a viewer may see an owner's statuses.
 * The decision combines user-service's answer (contact? blocked?) with the owner's mode and list (stored here).
 */
@Service
public class PrivacyService {

    private final StatusPrivacyRepository privacyRepository;
    private final StatusPrivacyMemberRepository memberRepository;
    private final UserClient userClient;

    public PrivacyService(StatusPrivacyRepository privacyRepository, StatusPrivacyMemberRepository memberRepository,
                          UserClient userClient) {
        this.privacyRepository = privacyRepository;
        this.memberRepository = memberRepository;
        this.userClient = userClient;
    }

    // ------------------------------------------------------------ my setting

    @Transactional(readOnly = true)
    public PrivacyResponse get(UUID ownerId) {
        PrivacyMode mode = modeOf(ownerId);
        List<UUID> userIds = mode == PrivacyMode.CONTACTS ? List.of() : sorted(memberRepository.findUserIds(ownerId));
        return new PrivacyResponse(mode, userIds);
    }

    /** Replaces the mode AND the list together (one transaction), so the two never disagree. */
    @Transactional
    public PrivacyResponse update(UUID ownerId, PrivacyRequest request) {
        // the same person twice is simply kept once
        List<UUID> userIds = request.userIds() == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(request.userIds()));
        Map<String, String> errors = new LinkedHashMap<>();
        if (request.mode() == PrivacyMode.CONTACTS && !userIds.isEmpty()) {
            errors.put("userIds", "must be empty when the mode is CONTACTS");
        }
        if (userIds.contains(ownerId)) {
            errors.put("userIds", "must not contain yourself");
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }

        StatusPrivacy privacy = privacyRepository.findById(ownerId)
                .orElseGet(() -> new StatusPrivacy(ownerId, request.mode(), Times.now()));
        privacy.setMode(request.mode());
        privacy.setUpdatedAt(Times.now());
        privacyRepository.save(privacy);

        memberRepository.deleteByOwner(ownerId);
        memberRepository.flush();
        memberRepository.saveAll(userIds.stream().map(userId -> new StatusPrivacyMember(ownerId, userId)).toList());
        return new PrivacyResponse(request.mode(), sorted(userIds));
    }

    // ------------------------------------------------------------ decisions

    /** May this viewer see this owner's statuses? (asks user-service: contact? blocked?) */
    public boolean maySee(UUID viewerId, UUID ownerId) {
        if (viewerId.equals(ownerId)) {
            return true;
        }
        UserClient.Relation relation = userClient.relation(ownerId, viewerId);
        if (relation.blocked() || !relation.contact()) {
            return false;   // no need to look at the mode
        }
        return VisibilityRules.maySee(viewerId, ownerId, relation.contact(), relation.blocked(),
                modeOf(ownerId), memberRepository.isListed(ownerId, viewerId));
    }

    /**
     * For the feed: of these owners (who all have the viewer as a contact and no block, as user-service told us),
     * the ones whose privacy mode lets the viewer see their statuses. Two queries for the whole feed.
     */
    @Transactional(readOnly = true)
    public Set<UUID> ownersVisibleTo(UUID viewerId, Collection<UUID> ownerIds) {
        if (ownerIds.isEmpty()) {
            return Set.of();
        }
        Map<UUID, PrivacyMode> modes = new HashMap<>();
        privacyRepository.findAllById(ownerIds).forEach(p -> modes.put(p.getOwnerId(), p.getMode()));
        Set<UUID> listing = Set.copyOf(memberRepository.findOwnersListing(viewerId, ownerIds));

        Set<UUID> visible = new LinkedHashSet<>();
        for (UUID ownerId : ownerIds) {
            if (VisibilityRules.maySee(viewerId, ownerId, true, false, modes.get(ownerId), listing.contains(ownerId))) {
                visible.add(ownerId);
            }
        }
        return visible;
    }

    private PrivacyMode modeOf(UUID ownerId) {
        return privacyRepository.findById(ownerId).map(StatusPrivacy::getMode).orElse(PrivacyMode.CONTACTS);
    }

    private static List<UUID> sorted(Collection<UUID> ids) {
        return ids.stream().sorted((a, b) -> a.toString().compareTo(b.toString())).toList();
    }
}
