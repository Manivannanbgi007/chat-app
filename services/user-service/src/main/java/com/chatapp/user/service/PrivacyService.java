package com.chatapp.user.service;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.user.dto.request.PrivacyRequest;
import com.chatapp.user.dto.response.PrivacyResponse;
import com.chatapp.user.entity.UserPrivacySettings;
import com.chatapp.user.exception.ResourceNotFoundException;
import com.chatapp.user.mapper.UserMapper;
import com.chatapp.user.repository.PrivacyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PrivacyService {

    private final PrivacyRepository privacyRepository;
    private final UserMapper userMapper;

    public PrivacyService(PrivacyRepository privacyRepository, UserMapper userMapper) {
        this.privacyRepository = privacyRepository;
        this.userMapper = userMapper;
    }

    @Transactional(readOnly = true)
    public PrivacyResponse getPrivacy(UUID myId) {
        return userMapper.toPrivacyResponse(find(myId));
    }

    @Transactional
    public PrivacyResponse updatePrivacy(UUID myId, PrivacyRequest request) {
        UserPrivacySettings settings = find(myId);
        userMapper.updatePrivacy(request, settings);
        settings.setUpdatedAt(Instant.now());
        return userMapper.toPrivacyResponse(privacyRepository.save(settings));
    }

    private UserPrivacySettings find(UUID myId) {
        return privacyRepository.findById(myId)
                .orElseThrow(() -> new ResourceNotFoundException("Profile not found"));
    }
}
