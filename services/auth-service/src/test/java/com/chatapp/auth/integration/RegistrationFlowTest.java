package com.chatapp.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import com.chatapp.auth.support.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class RegistrationFlowTest extends AbstractIntegrationTest {

    @Test
    void registerCreatesUserAndSendsVerificationCode() throws Exception {
        TestUser user = register();

        assertThat(emails.lastCodeFor(user.email())).matches("\\d{6}");
        Integer verified = jdbc.queryForObject(
                "SELECT email_verified FROM auth_users WHERE id = ?", Integer.class, user.userId());
        assertThat(verified).isZero();
    }

    @Test
    void passwordIsStoredAsBCryptHashNeverPlainText() throws Exception {
        TestUser user = register();

        String hash = jdbc.queryForObject("SELECT password_hash FROM auth_users WHERE id = ?", String.class, user.userId());
        assertThat(hash).startsWith("$2").doesNotContain(PASSWORD);
        String otpHash = jdbc.queryForObject("SELECT otp_hash FROM otp_verifications WHERE user_id = ?", String.class, user.userId());
        assertThat(otpHash).doesNotContain(emails.lastCodeFor(user.email()));
    }

    @Test
    void duplicateEmailUsernameAndPhoneAreRejectedWithCodes() throws Exception {
        TestUser user = register();

        MvcResult sameEmail = postJson("/api/auth/register", Map.of(
                "username", "other_" + UUID.randomUUID().toString().substring(0, 8),
                "email", user.email().toUpperCase(),     // email is case-insensitive
                "password", PASSWORD, "displayName", "X"));
        assertStatus(sameEmail, 409);
        assertThat(field(sameEmail, "$.data.errorCode")).isEqualTo("EMAIL_TAKEN");

        MvcResult sameUsername = postJson("/api/auth/register", Map.of(
                "username", user.username().toUpperCase(),
                "email", "new_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com",
                "password", PASSWORD, "displayName", "X"));
        assertStatus(sameUsername, 409);
        assertThat(field(sameUsername, "$.data.errorCode")).isEqualTo("USERNAME_TAKEN");

        String phone = "+9198" + (10000000 + (int) (Math.random() * 89999999));
        MvcResult first = postJson("/api/auth/register", Map.of(
                "username", "ph_" + UUID.randomUUID().toString().substring(0, 8),
                "email", "ph_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com",
                "phone", phone, "password", PASSWORD, "displayName", "X"));
        assertStatus(first, 201);
        MvcResult samePhone = postJson("/api/auth/register", Map.of(
                "username", "ph2_" + UUID.randomUUID().toString().substring(0, 8),
                "email", "ph2_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com",
                "phone", phone, "password", PASSWORD, "displayName", "X"));
        assertStatus(samePhone, 409);
        assertThat(field(samePhone, "$.data.errorCode")).isEqualTo("PHONE_TAKEN");
    }

    @Test
    void invalidInputReturnsFieldErrors() throws Exception {
        MvcResult result = postJson("/api/auth/register", Map.of(
                "username", "a",
                "email", "not-an-email",
                "phone", "12345",
                "password", "short",
                "displayName", ""));
        assertStatus(result, 400);
        assertThat(field(result, "$.message")).isEqualTo("Validation failed");
        for (String fieldName : List.of("username", "email", "phone", "password", "displayName")) {
            assertThat(field(result, "$.data." + fieldName)).as(fieldName).isNotBlank();
        }
    }

    @Test
    void passwordNeedsALetterAndADigit() throws Exception {
        MvcResult result = postJson("/api/auth/register", Map.of(
                "username", "pw_" + UUID.randomUUID().toString().substring(0, 8),
                "email", "pw_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com",
                "password", "onlyletters",
                "displayName", "X"));
        assertStatus(result, 400);
        assertThat(field(result, "$.data.password")).contains("letter and one digit");
    }

    @Test
    void wrongCodeIsRejectedAndRightCodeVerifiesTheEmail() throws Exception {
        TestUser user = register();
        String right = emails.lastCodeFor(user.email());
        String wrong = right.equals("000000") ? "111111" : "000000";

        MvcResult bad = postJson("/api/auth/verify-otp", Map.of("identifier", user.email(), "otp", wrong, "purpose", "EMAIL_VERIFY"));
        assertStatus(bad, 400);
        assertThat(field(bad, "$.data.errorCode")).isEqualTo("INVALID_OTP");

        MvcResult good = postJson("/api/auth/verify-otp", Map.of("identifier", user.email(), "otp", right, "purpose", "EMAIL_VERIFY"));
        assertStatus(good, 200);
        Integer verified = jdbc.queryForObject("SELECT email_verified FROM auth_users WHERE id = ?", Integer.class, user.userId());
        assertThat(verified).isEqualTo(1);

        // a code works only once
        MvcResult again = postJson("/api/auth/verify-otp", Map.of("identifier", user.email(), "otp", right, "purpose", "EMAIL_VERIFY"));
        assertStatus(again, 400);
    }

    @Test
    void tooManyWrongAttemptsBurnTheCode() throws Exception {
        TestUser user = register();
        String right = emails.lastCodeFor(user.email());
        String wrong = right.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 4; i++) {
            assertStatus(postJson("/api/auth/verify-otp",
                    Map.of("identifier", user.email(), "otp", wrong, "purpose", "EMAIL_VERIFY")), 400);
        }
        MvcResult fifth = postJson("/api/auth/verify-otp", Map.of("identifier", user.email(), "otp", wrong, "purpose", "EMAIL_VERIFY"));
        assertStatus(fifth, 429);
        assertThat(field(fifth, "$.data.errorCode")).isEqualTo("OTP_ATTEMPTS_EXCEEDED");

        // even the right code no longer works: the user must ask for a new one
        assertStatus(postJson("/api/auth/verify-otp",
                Map.of("identifier", user.email(), "otp", right, "purpose", "EMAIL_VERIFY")), 400);
    }

    @Test
    void expiredCodeIsRejected() throws Exception {
        TestUser user = register();
        setTimeFromNow("otp_verifications", "expires_at", "user_id", user.userId(), -5);

        MvcResult result = postJson("/api/auth/verify-otp",
                Map.of("identifier", user.email(), "otp", emails.lastCodeFor(user.email()), "purpose", "EMAIL_VERIFY"));
        assertStatus(result, 400);
    }

    @Test
    void resendIsLimitedToOnceAMinuteAndFivePerHour() throws Exception {
        TestUser user = register();
        Map<String, String> body = Map.of("identifier", user.email(), "purpose", "EMAIL_VERIFY");

        // right after registering: too soon
        MvcResult tooSoon = postJson("/api/auth/resend-otp", body);
        assertStatus(tooSoon, 429);
        assertThat(field(tooSoon, "$.data.errorCode")).isEqualTo("OTP_RATE_LIMITED");

        // pretend a minute passed: a new code is sent and the old one stops working
        String firstCode = emails.lastCodeFor(user.email());
        setTimeFromNow("otp_verifications", "created_at", "user_id", user.userId(), -120);
        assertStatus(postJson("/api/auth/resend-otp", body), 200);
        assertThat(emails.emailsSentTo(user.email())).isEqualTo(2);

        // the old code stopped working
        MvcResult oldCode = postJson("/api/auth/verify-otp",
                Map.of("identifier", user.email(), "otp", firstCode, "purpose", "EMAIL_VERIFY"));
        if (!firstCode.equals(emails.lastCodeFor(user.email()))) {
            assertStatus(oldCode, 400);
        }

        // hourly cap: 5 codes in the last hour (the 2 above + 3 more), the 6th is refused
        for (int i = 0; i < 3; i++) {
            setTimeFromNow("otp_verifications", "created_at", "user_id", user.userId(), -120);
            assertStatus(postJson("/api/auth/resend-otp", body), 200);
        }
        setTimeFromNow("otp_verifications", "created_at", "user_id", user.userId(), -120);
        assertStatus(postJson("/api/auth/resend-otp", body), 429);
    }

    @Test
    void resendForUnknownEmailLooksTheSame() throws Exception {
        MvcResult result = postJson("/api/auth/resend-otp",
                Map.of("identifier", "nobody-" + UUID.randomUUID() + "@example.com", "purpose", "EMAIL_VERIFY"));
        assertStatus(result, 200);
    }

    @Test
    void registrationQueuesAnEventThatReachesKafka() throws Exception {
        TestUser user = register();

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        ConsumerRecord<String, String> found = null;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("user.events"));
            long deadline = System.currentTimeMillis() + 30_000;
            while (found == null && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (user.userId().equals(record.key())) {
                        found = record;
                    }
                }
            }
        }

        assertThat(found).as("event for the new user on user.events").isNotNull();
        String json = found.value();
        assertThat((String) com.jayway.jsonpath.JsonPath.read(json, "$.eventType")).isEqualTo("UserRegisteredEvent");
        assertThat((Integer) com.jayway.jsonpath.JsonPath.read(json, "$.schemaVersion")).isEqualTo(1);
        assertThat((String) com.jayway.jsonpath.JsonPath.read(json, "$.payload.username")).isEqualTo(user.username());
        assertThat((String) com.jayway.jsonpath.JsonPath.read(json, "$.payload.email")).isEqualTo(user.email());
        assertThat(json).doesNotContain("password").doesNotContain("Passw0rd");

        // and the outbox row is marked as published
        Integer published = jdbc.queryForObject(
                "SELECT published FROM outbox_events WHERE aggregate_id = ?", Integer.class, user.userId());
        assertThat(published).isEqualTo(1);
    }

    @Test
    void auditTrailRecordsRegistration() throws Exception {
        TestUser user = register();
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE user_id = ? AND action = 'REGISTERED'", Integer.class, user.userId());
        assertThat(count).isEqualTo(1);
    }
}
