package com.chatapp.user.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.chatapp.user.entity.User;
import com.chatapp.user.entity.UserProfile;
import com.chatapp.user.exception.ResourceNotFoundException;
import com.chatapp.user.repository.UserProfileRepository;
import com.chatapp.user.repository.UserRepository;
import com.chatapp.user.repository.UserWithProfile;
import org.springframework.stereotype.Component;

/** Small helper: loads users together with their profiles. */
@Component
public class UserDirectory {

    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;

    public UserDirectory(UserRepository userRepository, UserProfileRepository profileRepository) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
    }

    /** Returns the user with profile or throws 404. */
    public UserWithProfile require(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        UserProfile profile = profileRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return new UserWithProfile(user, profile);
    }

    /** Loads many users with two queries. Unknown ids are simply left out. */
    public List<UserWithProfile> findAll(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, UserProfile> profiles = profileRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserProfile::getUserId, Function.identity()));
        List<UserWithProfile> result = new ArrayList<>();
        for (User user : userRepository.findAllById(userIds)) {
            UserProfile profile = profiles.get(user.getId());
            if (profile != null) {
                result.add(new UserWithProfile(user, profile));
            }
        }
        return result;
    }
}
