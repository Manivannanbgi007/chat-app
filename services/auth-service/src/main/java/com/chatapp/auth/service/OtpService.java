package com.chatapp.auth.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.chatapp.auth.config.AuthProperties;
import com.chatapp.auth.entity.AuthUser;
import com.chatapp.auth.entity.OtpVerification;
import com.chatapp.auth.enums.OtpPurpose;
import com.chatapp.auth.exception.BadRequestException;
import com.chatapp.auth.exception.TooManyRequestsException;
import com.chatapp.auth.repository.OtpVerificationRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time 6-digit codes: create + email them, and check them.
 * Rules: valid 10 minutes, 5 wrong attempts burn the code, at most 5 codes per hour,
 * at least 60 seconds between codes. Only a hash of the code is stored.
 */
@Service
public class OtpService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OtpVerificationRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final AuthProperties.Otp rules;

    public OtpService(OtpVerificationRepository repository, PasswordEncoder passwordEncoder,
                      EmailService emailService, AuthProperties properties) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.rules = properties.otp();
    }

    /** Creates a new code, invalidates older ones, and emails it. Throws 429 if the user asks too often. */
    @Transactional
    public void createAndSend(AuthUser user, OtpPurpose purpose) {
        Instant now = Instant.now();

        Optional<OtpVerification> latest = repository.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(user.getId(), purpose);
        if (latest.isPresent()) {
            long secondsSinceLast = Duration.between(latest.get().getCreatedAt(), now).toSeconds();
            if (secondsSinceLast < rules.resendSeconds()) {
                throw new TooManyRequestsException(
                        "Please wait " + (rules.resendSeconds() - secondsSinceLast) + " seconds before asking for a new code",
                        "OTP_RATE_LIMITED");
            }
        }
        long sentLastHour = repository.countByUserIdAndPurposeAndCreatedAtAfter(
                user.getId(), purpose, now.minus(Duration.ofHours(1)));
        if (sentLastHour >= rules.maxPerHour()) {
            throw new TooManyRequestsException("Too many codes requested. Please try again later", "OTP_RATE_LIMITED");
        }

        repository.invalidateUnused(user.getId(), purpose);

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        OtpVerification otp = new OtpVerification();
        otp.setUserId(user.getId());
        otp.setPurpose(purpose);
        otp.setOtpHash(passwordEncoder.encode(code));
        otp.setExpiresAt(now.plus(Duration.ofMinutes(rules.validMinutes())));
        otp.setCreatedAt(now);
        repository.save(otp);

        emailService.sendVerificationCode(user.getEmail(), user.getDisplayName(), code, rules.validMinutes());
    }

    /**
     * Checks a code. Returns normally if it is correct (and burns it); otherwise throws.
     * noRollbackFor on the callers keeps the attempt counter saved even though we throw.
     */
    @Transactional(noRollbackFor = {BadRequestException.class, TooManyRequestsException.class})
    public void verify(AuthUser user, OtpPurpose purpose, String code) {
        OtpVerification otp = repository
                .findFirstByUserIdAndPurposeAndUsedFalseOrderByCreatedAtDesc(user.getId(), purpose)
                .orElseThrow(() -> new BadRequestException("Invalid or expired code", "INVALID_OTP"));

        if (otp.getExpiresAt().isBefore(Instant.now())) {
            otp.setUsed(true);
            throw new BadRequestException("Invalid or expired code", "INVALID_OTP");
        }
        if (otp.getAttempts() >= rules.maxAttempts()) {
            otp.setUsed(true);
            throw new TooManyRequestsException("Too many wrong attempts. Please request a new code", "OTP_ATTEMPTS_EXCEEDED");
        }

        if (!passwordEncoder.matches(code, otp.getOtpHash())) {
            otp.setAttempts(otp.getAttempts() + 1);
            if (otp.getAttempts() >= rules.maxAttempts()) {
                otp.setUsed(true);
                throw new TooManyRequestsException("Too many wrong attempts. Please request a new code", "OTP_ATTEMPTS_EXCEEDED");
            }
            throw new BadRequestException("Invalid or expired code", "INVALID_OTP");
        }

        otp.setUsed(true);
    }
}
