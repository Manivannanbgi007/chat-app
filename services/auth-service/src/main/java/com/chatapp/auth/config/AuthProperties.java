package com.chatapp.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All our own settings from application.yml (prefix "chatapp"), in one typed place.
 */
@ConfigurationProperties(prefix = "chatapp")
public record AuthProperties(
        Security security,
        Otp otp,
        PasswordReset passwordReset,
        Mail mail,
        String frontendBaseUrl) {

    public record Security(
            String jwtSecret,
            int accessTokenMinutes,
            int refreshTokenDays,
            int maxFailedLogins,
            int lockMinutes) {
    }

    public record Otp(int validMinutes, int maxAttempts, int maxPerHour, int resendSeconds) {
    }

    public record PasswordReset(int validMinutes, int maxPerHour) {
    }

    public record Mail(String from) {
    }
}
