package com.chatapp.user.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import com.chatapp.user.entity.ProcessedEvent;
import com.chatapp.user.entity.User;
import com.chatapp.user.entity.UserPrivacySettings;
import com.chatapp.user.entity.UserProfile;
import com.chatapp.user.repository.PrivacyRepository;
import com.chatapp.user.repository.ProcessedEventRepository;
import com.chatapp.user.repository.UserProfileRepository;
import com.chatapp.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserRegistrationServiceTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserProfileRepository profileRepository;
    @Mock
    private PrivacyRepository privacyRepository;

    @InjectMocks
    private UserRegistrationService service;

    private final UUID eventId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    void newEventCreatesUserProfileAndPrivacyDefaults() {
        service.createUser(eventId, userId, "alice", "alice@example.com", "+919876543210", "Alice");

        verify(processedEventRepository).save(any(ProcessedEvent.class));
        verify(userRepository).save(any(User.class));
        verify(profileRepository).save(any(UserProfile.class));
        verify(privacyRepository).save(any(UserPrivacySettings.class));
    }

    @Test
    void alreadyProcessedEventIsSkipped() {
        when(processedEventRepository.existsById(eventId)).thenReturn(true);

        service.createUser(eventId, userId, "alice", "alice@example.com", null, "Alice");

        verify(processedEventRepository, never()).save(any());
        verify(userRepository, never()).save(any());
        verify(profileRepository, never()).save(any());
        verify(privacyRepository, never()).save(any());
    }

    @Test
    void existingUserIsNotCreatedAgainEvenForANewEventId() {
        when(userRepository.existsById(userId)).thenReturn(true);

        service.createUser(eventId, userId, "alice", "alice@example.com", null, "Alice");

        verify(processedEventRepository).save(any(ProcessedEvent.class));
        verify(userRepository, never()).save(any());
        verify(profileRepository, never()).save(any());
    }
}
