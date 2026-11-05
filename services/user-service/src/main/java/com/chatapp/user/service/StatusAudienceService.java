package com.chatapp.user.service;

import java.util.List;
import java.util.UUID;

import com.chatapp.user.dto.response.ContactRelationResponse;
import com.chatapp.user.repository.ContactRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers status-service's two questions (Phase 8). A status is shown to the people in the OWNER's contact list,
 * so the direction of the contact matters: "who saved me?" is not the same as "whom did I save?".
 * Blocks in either direction always win.
 */
@Service
public class StatusAudienceService {

    private final ContactRepository contactRepository;
    private final InternalLookupService lookupService;

    public StatusAudienceService(ContactRepository contactRepository, InternalLookupService lookupService) {
        this.contactRepository = contactRepository;
        this.lookupService = lookupService;
    }

    /** The users who have the viewer in their contact list, minus anybody blocked either way. */
    @Transactional(readOnly = true)
    public List<UUID> contactOwners(UUID viewerId) {
        return contactRepository.findOwnersWithContactNotBlocked(viewerId);
    }

    /** Is the viewer in the owner's contact list, and did either of them block the other? */
    @Transactional(readOnly = true)
    public ContactRelationResponse relation(UUID ownerId, UUID viewerId) {
        boolean contact = !ownerId.equals(viewerId) && contactRepository.existsByOwnerIdAndContactUserId(ownerId, viewerId);
        boolean blocked = lookupService.isBlockedEitherWay(ownerId, viewerId);
        return new ContactRelationResponse(contact, blocked);
    }
}
