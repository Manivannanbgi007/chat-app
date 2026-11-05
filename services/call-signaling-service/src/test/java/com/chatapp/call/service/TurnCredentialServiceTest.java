package com.chatapp.call.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.chatapp.call.config.AppProperties;
import com.chatapp.call.dto.response.IceServerResponse;
import org.junit.jupiter.api.Test;

/** TURN passwords must be exactly what Coturn computes, or the TURN server refuses the browser. */
class TurnCredentialServiceTest {

    private static final String SECRET = "test-turn-secret-0123456789";
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1_699_971_200L), ZoneOffset.UTC);

    private TurnCredentialService service(long ttlSeconds) {
        return new TurnCredentialService(new AppProperties.Turn(SECRET, "turn.example.com", 3478, ttlSeconds), CLOCK);
    }

    @Test
    void theCredentialIsBase64OfHmacSha1OfTheUsername() {
        // computed independently with: printf '%s' "<username>" | openssl dgst -sha1 -hmac "<secret>" -binary | base64
        assertThat(service(3600).credentialFor("1700000000:11111111-2222-3333-4444-555555555555"))
                .isEqualTo("MAWm7Muehpz59wFab8XcgJqu8fk=");
    }

    @Test
    void stunComesFirstAndTurnHasAUsernameThatExpires() {
        UUID user = UUID.fromString("11111111-2222-3333-4444-555555555555");

        List<IceServerResponse> servers = service(28_800).iceServersFor(user);

        assertThat(servers).hasSize(2);
        assertThat(servers.get(0).urls()).containsExactly("stun:turn.example.com:3478");
        assertThat(servers.get(0).username()).isNull();
        assertThat(servers.get(0).credential()).isNull();
        assertThat(servers.get(1).urls()).containsExactly(
                "turn:turn.example.com:3478?transport=udp", "turn:turn.example.com:3478?transport=tcp");
        // expiry = now + 8 hours
        assertThat(servers.get(1).username()).isEqualTo((1_699_971_200L + 28_800) + ":" + user);
        assertThat(servers.get(1).credential()).isEqualTo(service(28_800).credentialFor(servers.get(1).username()));
    }

    @Test
    void theEightHourCredentialOfThatUserIsTheOpensslVector() {
        // 1_699_971_200 + 28_800 = 1_700_000_000, so the 8-hour credential of that user is the openssl vector
        UUID user = UUID.fromString("11111111-2222-3333-4444-555555555555");
        assertThat(service(28_800).iceServersFor(user).get(1).credential()).isEqualTo("MAWm7Muehpz59wFab8XcgJqu8fk=");
    }

    @Test
    void aShortSecretIsRefusedAtStartUp() {
        assertThatThrownBy(() -> new TurnCredentialService(new AppProperties.Turn("short", "localhost", 3478, 60), CLOCK))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TurnCredentialService(new AppProperties.Turn(null, "localhost", 3478, 60), CLOCK))
                .isInstanceOf(IllegalStateException.class);
    }
}
