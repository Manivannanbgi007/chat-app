package com.chatapp.user.service;

import java.util.UUID;

import com.chatapp.user.entity.ProcessedEvent;
import com.chatapp.user.entity.User;
import com.chatapp.user.entity.UserPrivacySettings;
import com.chatapp.user.entity.UserProfile;
import com.chatapp.user.repository.PrivacyRepository;
import com.chatapp.user.repository.ProcessedEventRepository;
import com.chatapp.user.repository.UserProfileRepository;
import com.chatapp.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates the profile of a newly registered user (called by the Kafka consumer). */
@Service
public class UserRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(UserRegistrationService.class);

    private final ProcessedEventRepository processedEventRepository;
    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;
    private final PrivacyRepository privacyRepository;

    public UserRegistrationService(ProcessedEventRepository processedEventRepository,
                                   UserRepository userRepository,
                                   UserProfileRepository profileRepository,
                                   PrivacyRepository privacyRepository) {
        this.processedEventRepository = processedEventRepository;
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.privacyRepository = privacyRepository;
    }

    /**
     * All steps run in ONE transaction: either the event is marked as processed AND the user
     * exists, or nothing happened. Handling the same event twice does nothing the second time.
     */
    @Transactional
    public void createUser(UUID eventId, UUID userId, String username, String email, String phone, String displayName) {
        if (processedEventRepository.existsById(eventId)) {
            log.info("Event {} was already processed, skipping", eventId);
            return;
        }
        processedEventRepository.save(new ProcessedEvent(eventId));

        if (userRepository.existsById(userId)) {
            log.info("User {} already exists, nothing to create", userId);
            return;
        }
        userRepository.save(new User(userId, username, email, phone));
        profileRepository.save(new UserProfile(userId, displayName));
        privacyRepository.save(UserPrivacySettings.defaultsFor(userId));
        log.info("Created profile for user {}", userId);
    }
}
