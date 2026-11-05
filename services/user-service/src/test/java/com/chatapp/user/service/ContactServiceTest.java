package com.chatapp.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.chatapp.user.dto.request.AddContactRequest;
import com.chatapp.user.dto.response.ContactResponse;
import com.chatapp.user.dto.response.PublicProfileResponse;
import com.chatapp.user.entity.Contact;
import com.chatapp.user.entity.User;
import com.chatapp.user.entity.UserProfile;
import com.chatapp.user.exception.BadRequestException;
import com.chatapp.user.exception.ConflictException;
import com.chatapp.user.exception.ResourceNotFoundException;
import com.chatapp.user.repository.BlockedUserRepository;
import com.chatapp.user.repository.ContactRepository;
import com.chatapp.user.repository.UserWithProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class ContactServiceTest {

    @Mock
    private ContactRepository contactRepository;
    @Mock
    private BlockedUserRepository blockedUserRepository;
    @Mock
    private UserDirectory userDirectory;
    @Mock
    private ProfileVisibilityService visibilityService;

    @InjectMocks
    private ContactService service;

    private final UUID me = UUID.randomUUID();
    private final UUID friend = UUID.randomUUID();

    @Test
    void cannotAddYourself() {
        assertThatThrownBy(() -> service.addContact(me, new AddContactRequest(me, null)))
                .isInstanceOf(BadRequestException.class);
        verify(contactRepository, never()).saveAndFlush(any());
    }

    @Test
    void unknownUserIsNotFound() {
        when(userDirectory.require(friend)).thenThrow(new ResourceNotFoundException("User not found"));

        assertThatThrownBy(() -> service.addContact(me, new AddContactRequest(friend, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void userWhoBlockedMeLooksLikeUnknownUser() {
        when(userDirectory.require(friend)).thenReturn(userWithProfile(friend));
        when(blockedUserRepository.existsByBlockerIdAndBlockedId(friend, me)).thenReturn(true);

        assertThatThrownBy(() -> service.addContact(me, new AddContactRequest(friend, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(contactRepository, never()).saveAndFlush(any());
    }

    @Test
    void duplicateContactGivesAlreadyContactError() {
        when(userDirectory.require(friend)).thenReturn(userWithProfile(friend));
        when(contactRepository.existsByOwnerIdAndContactUserId(me, friend)).thenReturn(true);

        assertThatThrownBy(() -> service.addContact(me, new AddContactRequest(friend, null)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("ALREADY_CONTACT"));
    }

    @Test
    void raceOnTheUniqueKeyAlsoGivesAlreadyContactError() {
        when(userDirectory.require(friend)).thenReturn(userWithProfile(friend));
        when(contactRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> service.addContact(me, new AddContactRequest(friend, null)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("ALREADY_CONTACT"));
    }

    @Test
    void addsContactWithTrimmedNickname() {
        UserWithProfile target = userWithProfile(friend);
        when(userDirectory.require(friend)).thenReturn(target);
        when(contactRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(visibilityService.buildPublicProfiles(me, List.of(target)))
                .thenReturn(List.of(new PublicProfileResponse(friend, "pal", "PAL", null, null)));

        ContactResponse result = service.addContact(me, new AddContactRequest(friend, "  Buddy "));

        assertThat(result.nickname()).isEqualTo("Buddy");
        assertThat(result.contactUserId()).isEqualTo(friend);
        assertThat(result.user().username()).isEqualTo("pal");
    }

    @Test
    void blankNicknameIsStoredAsNull() {
        UserWithProfile target = userWithProfile(friend);
        when(userDirectory.require(friend)).thenReturn(target);
        when(contactRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(visibilityService.buildPublicProfiles(me, List.of(target)))
                .thenReturn(List.of(new PublicProfileResponse(friend, "pal", "PAL", null, null)));

        assertThat(service.addContact(me, new AddContactRequest(friend, "   ")).nickname()).isNull();
    }

    @Test
    void removeLooksUpTheContactByOwnerSoOthersCannotDeleteIt() {
        UUID contactId = UUID.randomUUID();
        when(contactRepository.findByIdAndOwnerId(contactId, me)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeContact(me, contactId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(contactRepository, never()).delete(any());
    }

    @Test
    void removeDeletesOwnContact() {
        UUID contactId = UUID.randomUUID();
        Contact contact = new Contact(me, friend, null);
        when(contactRepository.findByIdAndOwnerId(contactId, me)).thenReturn(Optional.of(contact));

        service.removeContact(me, contactId);

        verify(contactRepository).delete(contact);
    }

    @Test
    void listIsSortedByNicknameOrDisplayName() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Contact first = new Contact(me, a, "zed");     // shown as "zed"
        Contact second = new Contact(me, b, null);     // shown as display name "Anna"
        when(contactRepository.findByOwnerId(me)).thenReturn(List.of(first, second));
        List<UserWithProfile> users = List.of(userWithProfile(a, "Zoe"), userWithProfile(b, "Anna"));
        when(userDirectory.findAll(any())).thenReturn(users);
        when(visibilityService.buildPublicProfiles(me, users)).thenReturn(List.of(
                new PublicProfileResponse(a, "zoe", "Zoe", null, null),
                new PublicProfileResponse(b, "anna", "Anna", null, null)));

        List<ContactResponse> result = service.listContacts(me);

        assertThat(result).extracting(ContactResponse::contactUserId).containsExactly(b, a);
    }

    private static UserWithProfile userWithProfile(UUID id) {
        return userWithProfile(id, "PAL");
    }

    private static UserWithProfile userWithProfile(UUID id, String displayName) {
        return new UserWithProfile(
                new User(id, displayName.toLowerCase(), displayName.toLowerCase() + "@example.com", null),
                new UserProfile(id, displayName));
    }
}
