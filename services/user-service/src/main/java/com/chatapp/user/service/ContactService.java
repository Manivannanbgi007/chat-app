package com.chatapp.user.service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.chatapp.user.dto.request.AddContactRequest;
import com.chatapp.user.dto.response.ContactResponse;
import com.chatapp.user.dto.response.PublicProfileResponse;
import com.chatapp.user.entity.Contact;
import com.chatapp.user.exception.BadRequestException;
import com.chatapp.user.exception.ConflictException;
import com.chatapp.user.exception.ResourceNotFoundException;
import com.chatapp.user.repository.BlockedUserRepository;
import com.chatapp.user.repository.ContactRepository;
import com.chatapp.user.repository.UserWithProfile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContactService {

    private final ContactRepository contactRepository;
    private final BlockedUserRepository blockedUserRepository;
    private final UserDirectory userDirectory;
    private final ProfileVisibilityService visibilityService;

    public ContactService(ContactRepository contactRepository,
                          BlockedUserRepository blockedUserRepository,
                          UserDirectory userDirectory,
                          ProfileVisibilityService visibilityService) {
        this.contactRepository = contactRepository;
        this.blockedUserRepository = blockedUserRepository;
        this.userDirectory = userDirectory;
        this.visibilityService = visibilityService;
    }

    @Transactional
    public ContactResponse addContact(UUID myId, AddContactRequest request) {
        UUID contactUserId = request.contactUserId();
        if (contactUserId.equals(myId)) {
            throw new BadRequestException("You cannot add yourself as a contact");
        }

        UserWithProfile target = userDirectory.require(contactUserId); // 404 if unknown
        if (blockedUserRepository.existsByBlockerIdAndBlockedId(contactUserId, myId)) {
            throw new ResourceNotFoundException("User not found"); // do not reveal the block
        }
        if (contactRepository.existsByOwnerIdAndContactUserId(myId, contactUserId)) {
            throw alreadyContact();
        }

        String nickname = normalizeNickname(request.nickname());
        Contact contact;
        try {
            contact = contactRepository.saveAndFlush(new Contact(myId, contactUserId, nickname));
        } catch (DataIntegrityViolationException e) {
            // two requests at the same moment: the unique key stopped the second one
            throw alreadyContact();
        }

        PublicProfileResponse profile = visibilityService.buildPublicProfiles(myId, List.of(target)).get(0);
        return toResponse(contact, profile);
    }

    @Transactional(readOnly = true)
    public List<ContactResponse> listContacts(UUID myId) {
        List<Contact> contacts = contactRepository.findByOwnerId(myId);
        List<UserWithProfile> users = userDirectory.findAll(
                contacts.stream().map(Contact::getContactUserId).toList());
        Map<UUID, PublicProfileResponse> profiles = visibilityService.buildPublicProfiles(myId, users).stream()
                .collect(Collectors.toMap(PublicProfileResponse::id, Function.identity()));

        return contacts.stream()
                .filter(contact -> profiles.containsKey(contact.getContactUserId()))
                .map(contact -> toResponse(contact, profiles.get(contact.getContactUserId())))
                .sorted(Comparator.comparing(
                        (ContactResponse c) -> displayLabel(c).toLowerCase(Locale.ROOT)))
                .toList();
    }

    @Transactional
    public void removeContact(UUID myId, UUID contactId) {
        // Looking up by BOTH ids means nobody can delete somebody else's contact.
        Contact contact = contactRepository.findByIdAndOwnerId(contactId, myId)
                .orElseThrow(() -> new ResourceNotFoundException("Contact not found"));
        contactRepository.delete(contact);
    }

    private static String displayLabel(ContactResponse contact) {
        return contact.nickname() != null ? contact.nickname() : contact.user().displayName();
    }

    private static String normalizeNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return null;
        }
        return nickname.trim();
    }

    private static ConflictException alreadyContact() {
        return new ConflictException("This user is already in your contacts", "ALREADY_CONTACT");
    }

    private static ContactResponse toResponse(Contact contact, PublicProfileResponse profile) {
        return new ContactResponse(contact.getId(), contact.getContactUserId(),
                contact.getNickname(), contact.getCreatedAt(), profile);
    }
}
