package com.chatapp.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.chatapp.user.dto.request.UpdateProfileRequest;
import com.chatapp.user.dto.response.MyProfileResponse;
import com.chatapp.user.dto.response.PublicProfileResponse;
import com.chatapp.user.entity.User;
import com.chatapp.user.entity.UserProfile;
import com.chatapp.user.exception.BadRequestException;
import com.chatapp.user.exception.ResourceNotFoundException;
import com.chatapp.user.mapper.UserMapper;
import com.chatapp.user.mapper.UserMapperImpl;
import com.chatapp.user.repository.BlockedUserRepository;
import com.chatapp.user.repository.UserProfileRepository;
import com.chatapp.user.repository.UserRepository;
import com.chatapp.user.repository.UserWithProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserProfileRepository profileRepository;
    @Mock
    private BlockedUserRepository blockedUserRepository;
    @Mock
    private UserDirectory userDirectory;
    @Mock
    private ProfileVisibilityService visibilityService;

    private final UserMapper userMapper = new UserMapperImpl();

    private ProfileService service;

    private final UUID me = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ProfileService(userRepository, profileRepository, blockedUserRepository,
                userDirectory, visibilityService, userMapper);
    }

    // ---- search text handling ----

    @Test
    void escapeLikeNeutralisesWildcards() {
        assertThat(ProfileService.escapeLike("100%_a!")).isEqualTo("100!%!_a!!");
    }

    @Test
    void phoneCandidateAcceptsPhoneLikeTextOnly() {
        assertThat(ProfileService.phoneCandidate("+919876543210")).isEqualTo("+919876543210");
        assertThat(ProfileService.phoneCandidate("9876543210")).isEqualTo("+9876543210");
        assertThat(ProfileService.phoneCandidate("alice")).isEmpty();
        assertThat(ProfileService.phoneCandidate("12345")).isEmpty(); // too short
        assertThat(ProfileService.phoneCandidate("98765x43210")).isEmpty();
    }

    @Test
    void searchTextMustBeBetween2And50Characters() {
        assertThatThrownBy(() -> service.search(me, "a")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.search(me, "  a  ")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.search(me, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.search(me, "x".repeat(51))).isInstanceOf(BadRequestException.class);
        verify(userRepository, never()).search(any(), any(), any(), any(), any());
    }

    @Test
    void searchSendsLowerCasedEscapedPatternsAndLimit20() {
        when(userRepository.search(any(), any(), any(), any(), any())).thenReturn(List.of());

        service.search(me, "  50%_Al  ");

        ArgumentCaptor<String> prefix = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> contains = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> phone = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).search(eq(me), prefix.capture(), contains.capture(), phone.capture(), page.capture());

        assertThat(prefix.getValue()).isEqualTo("50!%!_al%");
        assertThat(contains.getValue()).isEqualTo("%50!%!_al%");
        assertThat(phone.getValue()).isEmpty();
        assertThat(page.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    void searchAppliesPrivacyToTheResults() {
        List<UserWithProfile> found = List.of(userWithProfile(other, "bob"));
        List<PublicProfileResponse> filtered = List.of(new PublicProfileResponse(other, "bob", "Bob", null, null));
        when(userRepository.search(any(), any(), any(), any(), any())).thenReturn(found);
        when(visibilityService.buildPublicProfiles(me, found)).thenReturn(filtered);

        assertThat(service.search(me, "bob")).isEqualTo(filtered);
    }

    // ---- public profile ----

    @Test
    void profileOfSomeoneWhoBlockedMeLooksLikeUnknownUser() {
        when(userDirectory.require(other)).thenReturn(userWithProfile(other, "bob"));
        when(blockedUserRepository.existsByBlockerIdAndBlockedId(other, me)).thenReturn(true);

        assertThatThrownBy(() -> service.getPublicProfile(me, other))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void ownProfileIsNeverCheckedForBlocks() {
        UserWithProfile mine = userWithProfile(me, "me");
        when(userDirectory.require(me)).thenReturn(mine);
        when(visibilityService.buildPublicProfiles(me, List.of(mine)))
                .thenReturn(List.of(new PublicProfileResponse(me, "me", "Me", null, "about")));

        PublicProfileResponse result = service.getPublicProfile(me, me);

        assertThat(result.username()).isEqualTo("me");
        verify(blockedUserRepository, never()).existsByBlockerIdAndBlockedId(any(), any());
    }

    // ---- own profile ----

    @Test
    void updateTrimsTextAndTurnsBlankAboutIntoNull() {
        UserWithProfile mine = userWithProfile(me, "me");
        when(userDirectory.require(me)).thenReturn(mine);

        MyProfileResponse result = service.updateMyProfile(me, new UpdateProfileRequest("  New Name  ", "   "));

        assertThat(result.displayName()).isEqualTo("New Name");
        assertThat(result.about()).isNull();
        verify(profileRepository).save(mine.profile());
    }

    @Test
    void updateKeepsRealAboutText() {
        UserWithProfile mine = userWithProfile(me, "me");
        when(userDirectory.require(me)).thenReturn(mine);

        MyProfileResponse result = service.updateMyProfile(me, new UpdateProfileRequest("Me", " busy "));

        assertThat(result.about()).isEqualTo("busy");
    }

    @Test
    void myProfileContainsPrivateFields() {
        UserWithProfile mine = userWithProfile(me, "me");
        when(userDirectory.require(me)).thenReturn(mine);

        MyProfileResponse result = service.getMyProfile(me);

        assertThat(result.email()).isEqualTo("me@example.com");
        assertThat(result.id()).isEqualTo(me);
    }

    private static UserWithProfile userWithProfile(UUID id, String username) {
        return new UserWithProfile(
                new User(id, username, username + "@example.com", "+919876543210"),
                new UserProfile(id, username.toUpperCase()));
    }
}
