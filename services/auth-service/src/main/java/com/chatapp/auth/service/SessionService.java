package com.chatapp.auth.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.chatapp.auth.dto.response.SessionResponse;
import com.chatapp.auth.entity.LoginSession;
import com.chatapp.auth.enums.AuditAction;
import com.chatapp.auth.exception.ResourceNotFoundException;
import com.chatapp.auth.mapper.SessionMapper;
import com.chatapp.auth.repository.LoginSessionRepository;
import com.chatapp.auth.repository.RefreshTokenRepository;
import com.chatapp.auth.util.ClientInfo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Login sessions = "active devices". Creating, listing and ending them. */
@Service
public class SessionService {

    private static final String DEFAULT_DEVICE_NAME = "Unknown device";

    private final LoginSessionRepository sessionRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SessionRevocationService revocationService;
    private final AuditService auditService;
    private final SessionMapper sessionMapper;

    public SessionService(LoginSessionRepository sessionRepository, RefreshTokenRepository refreshTokenRepository,
                          SessionRevocationService revocationService, AuditService auditService,
                          SessionMapper sessionMapper) {
        this.sessionRepository = sessionRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.revocationService = revocationService;
        this.auditService = auditService;
        this.sessionMapper = sessionMapper;
    }

    @Transactional
    public LoginSession create(UUID userId, String deviceName, ClientInfo client) {
        Instant now = Instant.now();
        LoginSession session = new LoginSession();
        session.setUserId(userId);
        session.setDeviceName(deviceName == null || deviceName.isBlank() ? DEFAULT_DEVICE_NAME : deviceName.trim());
        session.setUserAgent(client.userAgent());
        session.setIpAddress(client.ipAddress());
        session.setCreatedAt(now);
        session.setLastActiveAt(now);
        return sessionRepository.save(session);
    }

    @Transactional(readOnly = true)
    public List<SessionResponse> listActive(UUID userId, UUID currentSessionId) {
        return sessionRepository.findByUserIdAndRevokedFalseOrderByLastActiveAtDesc(userId).stream()
                .map(session -> sessionMapper.toResponse(session, session.getId().equals(currentSessionId)))
                .toList();
    }

    /** The user ends one of their own sessions (404 if it is not theirs). */
    @Transactional
    public void endOwnSession(UUID userId, UUID sessionId, String ipAddress) {
        LoginSession session = sessionRepository.findByIdAndUserIdAndRevokedFalse(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found"));
        revoke(session);
        auditService.record(userId, AuditAction.SESSION_REVOKED, ipAddress);
    }

    /** Ends a session: it can no longer refresh, and the gateway rejects its access token. */
    @Transactional
    public void revoke(LoginSession session) {
        session.setRevoked(true);
        refreshTokenRepository.revokeAllBySessionId(session.getId());
        revocationService.markRevoked(session.getId());
    }

    /** Ends every session of a user (after a password reset). */
    @Transactional
    public void revokeAllForUser(UUID userId) {
        for (LoginSession session : sessionRepository.findByUserIdAndRevokedFalseOrderByLastActiveAtDesc(userId)) {
            revoke(session);
        }
    }
}
