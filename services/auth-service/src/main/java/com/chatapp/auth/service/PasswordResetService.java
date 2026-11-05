package com.chatapp.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.chatapp.auth.config.AuthProperties;
import com.chatapp.auth.dto.request.ForgotPasswordRequest;
import com.chatapp.auth.dto.request.ResetPasswordRequest;
import com.chatapp.auth.entity.AuthUser;
import com.chatapp.auth.entity.PasswordResetToken;
import com.chatapp.auth.enums.AuditAction;
import com.chatapp.auth.exception.BadRequestException;
import com.chatapp.auth.repository.AuthUserRepository;
import com.chatapp.auth.repository.PasswordResetTokenRepository;
import com.chatapp.auth.util.ClientInfo;
import com.chatapp.auth.util.HashUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** "Forgot password": email a one-time link, then set a new password with it. */
@Service
public class PasswordResetService {

    private final AuthUserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final UserLookupService userLookup;
    private final TokenService tokenService;
    private final SessionService sessionService;
    private final EmailService emailService;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    public PasswordResetService(AuthUserRepository userRepository, PasswordResetTokenRepository tokenRepository,
                                UserLookupService userLookup, TokenService tokenService, SessionService sessionService,
                                EmailService emailService, AuditService auditService, PasswordEncoder passwordEncoder,
                                AuthProperties properties) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.userLookup = userLookup;
        this.tokenService = tokenService;
        this.sessionService = sessionService;
        this.emailService = emailService;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    /** Always succeeds from the caller's point of view, so nobody can find out which emails are registered. */
    @Transactional
    public void requestReset(ForgotPasswordRequest request, ClientInfo client) {
        Optional<AuthUser> found = userLookup.findByIdentifier(request.identifier());
        if (found.isEmpty()) {
            return;
        }
        AuthUser user = found.get();
        Instant now = Instant.now();

        long lastHour = tokenRepository.countByUserIdAndCreatedAtAfter(user.getId(), now.minus(Duration.ofHours(1)));
        if (lastHour >= properties.passwordReset().maxPerHour()) {
            return; // quietly ignore: protects the inbox of the account owner
        }

        tokenRepository.invalidateUnused(user.getId());

        String rawToken = tokenService.newOpaqueToken();
        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(user.getId());
        token.setTokenHash(HashUtil.sha256Hex(rawToken));
        token.setExpiresAt(now.plus(Duration.ofMinutes(properties.passwordReset().validMinutes())));
        token.setCreatedAt(now);
        tokenRepository.save(token);

        auditService.record(user.getId(), AuditAction.PASSWORD_RESET_REQUESTED, client.ipAddress());

        String link = properties.frontendBaseUrl() + "/reset-password?token=" + rawToken;
        emailService.sendPasswordResetLink(user.getEmail(), user.getDisplayName(), link,
                properties.passwordReset().validMinutes());
    }

    /** Sets the new password, burns the token, and logs the user out of every device. */
    @Transactional
    public void resetPassword(ResetPasswordRequest request, ClientInfo client) {
        PasswordResetToken token = tokenRepository.findByTokenHash(HashUtil.sha256Hex(request.token()))
                .filter(t -> !t.isUsed() && t.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new BadRequestException("This reset link is invalid or has expired", "INVALID_RESET_TOKEN"));

        AuthUser user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new BadRequestException("This reset link is invalid or has expired", "INVALID_RESET_TOKEN"));

        token.setUsed(true);
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setUpdatedAt(Instant.now());

        sessionService.revokeAllForUser(user.getId());
        auditService.record(user.getId(), AuditAction.PASSWORD_RESET, client.ipAddress());
    }
}
