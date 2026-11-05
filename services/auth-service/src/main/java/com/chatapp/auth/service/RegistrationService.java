package com.chatapp.auth.service;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import com.chatapp.auth.dto.request.RegisterRequest;
import com.chatapp.auth.dto.request.ResendOtpRequest;
import com.chatapp.auth.dto.request.VerifyOtpRequest;
import com.chatapp.auth.dto.response.RegisterResponse;
import com.chatapp.auth.entity.AuthUser;
import com.chatapp.auth.enums.AccountStatus;
import com.chatapp.auth.enums.AuditAction;
import com.chatapp.auth.enums.OtpPurpose;
import com.chatapp.auth.enums.Role;
import com.chatapp.auth.exception.BadRequestException;
import com.chatapp.auth.exception.ConflictException;
import com.chatapp.auth.exception.TooManyRequestsException;
import com.chatapp.auth.kafka.producer.OutboxService;
import com.chatapp.auth.mapper.UserMapper;
import com.chatapp.auth.repository.AuthUserRepository;
import com.chatapp.auth.util.ClientInfo;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creating an account and verifying the email address with a one-time code. */
@Service
public class RegistrationService {

    private final AuthUserRepository userRepository;
    private final UserLookupService userLookup;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;
    private final OutboxService outboxService;
    private final AuditService auditService;
    private final UserMapper userMapper;

    public RegistrationService(AuthUserRepository userRepository, UserLookupService userLookup,
                               PasswordEncoder passwordEncoder, OtpService otpService, OutboxService outboxService,
                               AuditService auditService, UserMapper userMapper) {
        this.userRepository = userRepository;
        this.userLookup = userLookup;
        this.passwordEncoder = passwordEncoder;
        this.otpService = otpService;
        this.outboxService = outboxService;
        this.auditService = auditService;
        this.userMapper = userMapper;
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request, ClientInfo client) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String username = request.username().trim().toLowerCase(Locale.ROOT);
        String phone = (request.phone() == null || request.phone().isBlank()) ? null : request.phone().trim();

        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("This email is already registered", "EMAIL_TAKEN");
        }
        if (phone != null && userRepository.existsByPhone(phone)) {
            throw new ConflictException("This phone number is already registered", "PHONE_TAKEN");
        }
        if (userRepository.existsByUsername(username)) {
            throw new ConflictException("This username is already taken", "USERNAME_TAKEN");
        }

        Instant now = Instant.now();
        AuthUser user = new AuthUser();
        user.setUsername(username);
        user.setDisplayName(request.displayName().trim());
        user.setEmail(email);
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(Role.USER);
        user.setStatus(AccountStatus.ACTIVE);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userRepository.save(user);

        // Same transaction as the user row: the event exists if and only if the user exists.
        outboxService.queue("UserRegisteredEvent", user.getId(), userMapper.toUserRegisteredPayload(user));
        auditService.record(user.getId(), AuditAction.REGISTERED, client.ipAddress());

        // Last step, because an email cannot be rolled back.
        otpService.createAndSend(user, OtpPurpose.EMAIL_VERIFY);

        return new RegisterResponse(user.getId(), user.getEmail(), true);
    }

    /** Checks the code from the email. Wrong, expired and unknown users all give the same answer. */
    @Transactional(noRollbackFor = {BadRequestException.class, TooManyRequestsException.class})
    public void verifyOtp(VerifyOtpRequest request, ClientInfo client) {
        AuthUser user = userLookup.findByIdentifier(request.identifier())
                .orElseThrow(() -> new BadRequestException("Invalid or expired code", "INVALID_OTP"));

        otpService.verify(user, request.purpose(), request.otp());

        user.setEmailVerified(true);
        user.setUpdatedAt(Instant.now());
        auditService.record(user.getId(), AuditAction.EMAIL_VERIFIED, client.ipAddress());
    }

    /** Sends a new code. Unknown or already verified accounts get the same "ok" so nobody can probe for emails. */
    @Transactional
    public void resendOtp(ResendOtpRequest request) {
        Optional<AuthUser> user = userLookup.findByIdentifier(request.identifier());
        if (user.isPresent() && !user.get().isEmailVerified()) {
            otpService.createAndSend(user.get(), request.purpose());
        }
    }
}
