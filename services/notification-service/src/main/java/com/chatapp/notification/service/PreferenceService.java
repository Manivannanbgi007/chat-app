package com.chatapp.notification.service;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.chatapp.notification.dto.request.PreferencesRequest;
import com.chatapp.notification.dto.response.PreferencesResponse;
import com.chatapp.notification.entity.NotificationPreference;
import com.chatapp.notification.mapper.NotificationMapper;
import com.chatapp.notification.repository.PreferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The user's notification switches. A user who never changed anything has everything on. */
@Service
public class PreferenceService {

    private final PreferenceRepository repository;
    private final NotificationMapper mapper;

    public PreferenceService(PreferenceRepository repository, NotificationMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PreferencesResponse get(UUID userId) {
        return mapper.toResponse(repository.findById(userId).orElseGet(() -> NotificationPreference.defaultsFor(userId)));
    }

    @Transactional
    public PreferencesResponse update(UUID userId, PreferencesRequest request) {
        NotificationPreference preference = repository.findById(userId)
                .orElseGet(() -> NotificationPreference.defaultsFor(userId));
        preference.setPushEnabled(request.pushEnabled());
        preference.setMessagesEnabled(request.messagesEnabled());
        preference.setGroupsEnabled(request.groupsEnabled());
        preference.setCallsEnabled(request.callsEnabled());
        preference.setUpdatedAt(Instant.now());
        return mapper.toResponse(repository.save(preference));
    }

    /** The preferences of several users at once (used by the Kafka handlers); users without a row get the defaults. */
    @Transactional(readOnly = true)
    public Map<UUID, NotificationPreference> loadAll(Collection<UUID> userIds) {
        Map<UUID, NotificationPreference> result = new HashMap<>();
        repository.findAllById(userIds).forEach(preference -> result.put(preference.getUserId(), preference));
        for (UUID userId : userIds) {
            result.computeIfAbsent(userId, NotificationPreference::defaultsFor);
        }
        return result;
    }
}
