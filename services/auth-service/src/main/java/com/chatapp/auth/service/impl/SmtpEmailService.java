package com.chatapp.auth.service.impl;

import com.chatapp.auth.config.AuthProperties;
import com.chatapp.auth.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends plain-text emails over SMTP (MailHog locally, a real provider in production).
 * A mail problem must not break registration, so failures are logged and the user can ask for a new code.
 * The log never contains the code or the link.
 */
@Service
public class SmtpEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailService.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailService(JavaMailSender mailSender, AuthProperties properties) {
        this.mailSender = mailSender;
        this.from = properties.mail().from();
    }

    @Override
    public void sendVerificationCode(String toEmail, String displayName, String code, int validMinutes) {
        send(toEmail, "Your chat-app verification code",
                "Hi " + displayName + ",\n\n"
                        + "Your verification code is: " + code + "\n"
                        + "It is valid for " + validMinutes + " minutes.\n\n"
                        + "If you did not create an account, you can ignore this email.");
    }

    @Override
    public void sendPasswordResetLink(String toEmail, String displayName, String resetLink, int validMinutes) {
        send(toEmail, "Reset your chat-app password",
                "Hi " + displayName + ",\n\n"
                        + "Use this link to choose a new password (valid for " + validMinutes + " minutes):\n"
                        + resetLink + "\n\n"
                        + "If you did not ask for this, you can ignore this email.");
    }

    private void send(String to, String subject, String text) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            mailSender.send(message);
        } catch (Exception e) {
            log.warn("Could not send email '{}' ({})", subject, e.getClass().getSimpleName());
        }
    }
}
