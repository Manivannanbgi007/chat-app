package com.chatapp.auth.service;

/**
 * Sends the emails the auth service needs. An interface, so tests can replace it
 * and read the codes without a real mail server.
 */
public interface EmailService {

    void sendVerificationCode(String toEmail, String displayName, String code, int validMinutes);

    void sendPasswordResetLink(String toEmail, String displayName, String resetLink, int validMinutes);
}
