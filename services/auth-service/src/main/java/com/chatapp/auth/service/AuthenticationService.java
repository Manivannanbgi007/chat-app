package com.chatapp.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.chatapp.auth.config.AuthProperties;
import com.chatapp.auth.dto.request.LoginRequest;
import com.chatapp.auth.dto.response.AuthTokensResponse;
import com.chatapp.auth.entity.AuthUser;
import com.chatapp.auth.entity.LoginSession;
import com.chatapp.auth.entity.RefreshToken;
import com.chatapp.auth.enums.AccountStatus;
import com.chatapp.auth.enums.AuditAction;
import com.chatapp.auth.exception.ApiException;
import com.chatapp.auth.exception.ForbiddenException;
import com.chatapp.auth.exception.UnauthorizedException;
import com.chatapp.auth.mapper.UserMapper;
import com.chatapp.auth.repository.AuthUserRepository;
import com.chatapp.auth.repository.LoginSessionRepository;
import com.chatapp.auth.repository.RefreshTokenRepository;
import com.chatapp.auth.util.ClientInfo;
import com.chatapp.auth.util.HashUtil;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login, refresh-token rotation and logout.
 *
 * Methods that throw after changing data use noRollbackFor, so the change
 * (failed-login counter, revoked session) is saved even though the request fails.
 */
@Service
public class AuthenticationService {

    private final AuthUserRepository userRepository;
    private final LoginSessionRepository sessionRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserLookupService userLookup;
    private final SessionService sessionService;
    private final TokenService tokenService;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final AuthProperties.Security rules;

    /** Used when the user does not exist, so a wrong email takes as long as a wrong password. */
    private final String dummyHash;

    public AuthenticationService(AuthUserRepository userRepository, LoginSessionRepository sessionRepository,
                                 RefreshTokenRepository refreshTokenRepository, UserLookupService userLookup,
                                 SessionService sessionService, TokenService tokenService, AuditService auditService,
                                 PasswordEncoder passwordEncoder, UserMapper userMapper, AuthProperties properties) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userLookup = userLookup;
        this.sessionService = sessionService;
        this.tokenService = tokenService;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
        this.userMapper = userMapper;
        this.rules = properties.security();
        this.dummyHash = passwordEncoder.encode("not-a-real-password-1");
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthTokensResponse login(LoginRequest request, ClientInfo client) {
        Optional<AuthUser> found = userLookup.findByIdentifier(request.identifier());
        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), dummyHash);
            auditService.record(null, AuditAction.LOGIN_FAILED, client.ipAddress());
            throw invalidCredentials();
        }
        AuthUser user = found.get();
        Instant now = Instant.now();

        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            throw new ApiException(HttpStatus.LOCKED, "Account is temporarily locked. Try again later", "ACCOUNT_LOCKED");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            registerFailedLogin(user, now, client);
            throw invalidCredentials();
        }

        // Checked after the password, so only the real owner learns the email is unverified.
        if (!user.isEmailVerified()) {
            throw new ForbiddenException("Please verify your email first", "EMAIL_NOT_VERIFIED");
        }
        if (user.getStatus() != AccountStatus.ACTIVE) {
            throw new ForbiddenException("This account is disabled", "ACCOUNT_DISABLED");
        }

        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setUpdatedAt(now);

        LoginSession session = sessionService.create(user.getId(), request.deviceName(), client);
        auditService.record(user.getId(), AuditAction.LOGIN_SUCCESS, client.ipAddress());
        return issueTokens(user, session);
    }

    /**
     * Swaps a refresh token for a new pair. The old refresh token stops working.
     * If an old (already used) token shows up again, someone copied it: the whole session is ended.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public AuthTokensResponse refresh(String refreshTokenValue, ClientInfo client) {
        RefreshToken stored = refreshTokenRepository.findByTokenHash(HashUtil.sha256Hex(refreshTokenValue))
                .orElseThrow(this::invalidRefreshToken);

        LoginSession session = sessionRepository.findById(stored.getSessionId()).orElseThrow(this::invalidRefreshToken);

        if (stored.isRevoked()) {
            if (!session.isRevoked()) {
                sessionService.revoke(session);
                auditService.record(stored.getUserId(), AuditAction.TOKEN_REUSE_DETECTED, client.ipAddress());
            }
            throw invalidRefreshToken();
        }
        if (stored.getExpiresAt().isBefore(Instant.now()) || session.isRevoked()) {
            throw invalidRefreshToken();
        }

        AuthUser user = userRepository.findById(stored.getUserId()).orElseThrow(this::invalidRefreshToken);
        if (user.getStatus() != AccountStatus.ACTIVE) {
            throw invalidRefreshToken();
        }

        session.setLastActiveAt(Instant.now());
        stored.setRevoked(true);
        AuthTokensResponse tokens = issueTokens(user, session, stored);
        return tokens;
    }

    /** Public and idempotent: whoever holds a refresh token may end its session. Unknown tokens are ignored. */
    @Transactional
    public void logout(String refreshTokenValue, ClientInfo client) {
        refreshTokenRepository.findByTokenHash(HashUtil.sha256Hex(refreshTokenValue)).ifPresent(stored ->
                sessionRepository.findById(stored.getSessionId()).ifPresent(session -> {
                    if (!session.isRevoked()) {
                        sessionService.revoke(session);
                        auditService.record(stored.getUserId(), AuditAction.LOGOUT, client.ipAddress());
                    }
                }));
    }

    private void registerFailedLogin(AuthUser user, Instant now, ClientInfo client) {
        int failures = user.getFailedLoginCount() + 1;
        auditService.record(user.getId(), AuditAction.LOGIN_FAILED, client.ipAddress());
        if (failures >= rules.maxFailedLogins()) {
            user.setFailedLoginCount(0);
            user.setLockedUntil(now.plus(Duration.ofMinutes(rules.lockMinutes())));
            auditService.record(user.getId(), AuditAction.ACCOUNT_LOCKED, client.ipAddress());
        } else {
            user.setFailedLoginCount(failures);
        }
        user.setUpdatedAt(now);
    }

    private AuthTokensResponse issueTokens(AuthUser user, LoginSession session) {
        return issueTokens(user, session, null);
    }

    /** Creates the new refresh token row (and links the old one to it when rotating) plus a new access token. */
    private AuthTokensResponse issueTokens(AuthUser user, LoginSession session, RefreshToken replaced) {
        String refreshValue = tokenService.newOpaqueToken();
        Instant now = Instant.now();

        RefreshToken token = new RefreshToken();
        token.setUserId(user.getId());
        token.setSessionId(session.getId());
        token.setTokenHash(HashUtil.sha256Hex(refreshValue));
        token.setExpiresAt(now.plus(Duration.ofDays(rules.refreshTokenDays())));
        token.setCreatedAt(now);
        refreshTokenRepository.save(token);

        if (replaced != null) {
            replaced.setReplacedById(token.getId());
        }

        return new AuthTokensResponse(
                tokenService.createAccessToken(user, session.getId()),
                refreshValue,
                "Bearer",
                tokenService.accessTokenSeconds(),
                userMapper.toSummary(user));
    }

    private UnauthorizedException invalidCredentials() {
        return new UnauthorizedException("Invalid email/phone or password", "INVALID_CREDENTIALS");
    }

    private UnauthorizedException invalidRefreshToken() {
        return new UnauthorizedException("Please log in again", "INVALID_REFRESH_TOKEN");
    }
}
