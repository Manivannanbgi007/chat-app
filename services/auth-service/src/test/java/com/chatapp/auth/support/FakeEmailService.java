package com.chatapp.auth.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.chatapp.auth.service.EmailService;

/** Test replacement for the SMTP sender: remembers the last code / link sent to each address. */
public class FakeEmailService implements EmailService {

    private final Map<String, String> lastCode = new ConcurrentHashMap<>();
    private final Map<String, String> lastResetLink = new ConcurrentHashMap<>();
    private final Map<String, Integer> sentCount = new ConcurrentHashMap<>();

    @Override
    public void sendVerificationCode(String toEmail, String displayName, String code, int validMinutes) {
        lastCode.put(toEmail, code);
        sentCount.merge(toEmail, 1, Integer::sum);
    }

    @Override
    public void sendPasswordResetLink(String toEmail, String displayName, String resetLink, int validMinutes) {
        lastResetLink.put(toEmail, resetLink);
        sentCount.merge(toEmail, 1, Integer::sum);
    }

    public String lastCodeFor(String email) {
        return lastCode.get(email);
    }

    public String lastResetLinkFor(String email) {
        return lastResetLink.get(email);
    }

    public int emailsSentTo(String email) {
        return sentCount.getOrDefault(email, 0);
    }
}
