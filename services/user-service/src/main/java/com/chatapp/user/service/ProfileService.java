package com.chatapp.user.service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.chatapp.user.dto.request.UpdateProfileRequest;
import com.chatapp.user.dto.response.MyProfileResponse;
import com.chatapp.user.dto.response.PublicProfileResponse;
import com.chatapp.user.entity.UserProfile;
import com.chatapp.user.exception.BadRequestException;
import com.chatapp.user.exception.ResourceNotFoundException;
import com.chatapp.user.mapper.UserMapper;
import com.chatapp.user.repository.BlockedUserRepository;
import com.chatapp.user.repository.UserProfileRepository;
import com.chatapp.user.repository.UserRepository;
import com.chatapp.user.repository.UserWithProfile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {

    private static final int SEARCH_MIN_LENGTH = 2;
    private static final int SEARCH_MAX_LENGTH = 50;
    private static final int SEARCH_LIMIT = 20;
    private static final Pattern PHONE_LIKE = Pattern.compile("\\+?[0-9]{7,15}");

    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;
    private final BlockedUserRepository blockedUserRepository;
    private final UserDirectory userDirectory;
    private final ProfileVisibilityService visibilityService;
    private final UserMapper userMapper;

    public ProfileService(UserRepository userRepository,
                          UserProfileRepository profileRepository,
                          BlockedUserRepository blockedUserRepository,
                          UserDirectory userDirectory,
                          ProfileVisibilityService visibilityService,
                          UserMapper userMapper) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.blockedUserRepository = blockedUserRepository;
        this.userDirectory = userDirectory;
        this.visibilityService = visibilityService;
        this.userMapper = userMapper;
    }

    @Transactional(readOnly = true)
    public MyProfileResponse getMyProfile(UUID myId) {
        UserWithProfile me = userDirectory.require(myId);
        return userMapper.toMyProfile(me.user(), me.profile());
    }

    @Transactional
    public MyProfileResponse updateMyProfile(UUID myId, UpdateProfileRequest request) {
        UserWithProfile me = userDirectory.require(myId);
        UserProfile profile = me.profile();

        profile.setDisplayName(request.displayName().trim());
        String about = request.about() == null ? "" : request.about().trim();
        profile.setAbout(about.isEmpty() ? null : about);
        profile.setUpdatedAt(java.time.Instant.now());
        profileRepository.save(profile);

        return userMapper.toMyProfile(me.user(), profile);
    }

    @Transactional(readOnly = true)
    public PublicProfileResponse getPublicProfile(UUID viewerId, UUID targetId) {
        UserWithProfile target = userDirectory.require(targetId);
        boolean blockedByTarget = !viewerId.equals(targetId)
                && blockedUserRepository.existsByBlockerIdAndBlockedId(targetId, viewerId);
        if (blockedByTarget) {
            // Same answer as "no such user", so a blocked person cannot tell they were blocked.
            throw new ResourceNotFoundException("User not found");
        }
        return visibilityService.buildPublicProfiles(viewerId, List.of(target)).get(0);
    }

    @Transactional(readOnly = true)
    public List<PublicProfileResponse> search(UUID myId, String text) {
        String q = text == null ? "" : text.trim();
        if (q.length() < SEARCH_MIN_LENGTH || q.length() > SEARCH_MAX_LENGTH) {
            throw new BadRequestException("Search text must be between "
                    + SEARCH_MIN_LENGTH + " and " + SEARCH_MAX_LENGTH + " characters");
        }

        String escaped = escapeLike(q.toLowerCase(Locale.ROOT));
        List<UserWithProfile> found = userRepository.search(
                myId,
                escaped + "%",            // username starts with
                "%" + escaped + "%",      // display name contains
                phoneCandidate(q),
                PageRequest.of(0, SEARCH_LIMIT));
        return visibilityService.buildPublicProfiles(myId, found);
    }

    /**
     * Makes user text safe for a LIKE query: "%" and "_" would otherwise act as wildcards.
     * "!" is the escape character used in UserRepository.search.
     */
    static String escapeLike(String text) {
        return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /** If the text looks like a phone number returns it in "+digits" form, otherwise "" (matches nothing). */
    static String phoneCandidate(String text) {
        if (!PHONE_LIKE.matcher(text).matches()) {
            return "";
        }
        return text.startsWith("+") ? text : "+" + text;
    }
}
