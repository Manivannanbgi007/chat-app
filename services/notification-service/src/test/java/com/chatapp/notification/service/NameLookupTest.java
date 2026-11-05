package com.chatapp.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.chatapp.notification.client.UserClient;
import org.junit.jupiter.api.Test;

class NameLookupTest {

    /** A user-service that knows one person and counts the questions. */
    private static class CountingUserClient implements UserClient {
        final AtomicInteger calls = new AtomicInteger();
        final UUID knownUser = UUID.randomUUID();

        @Override
        public Optional<String> displayName(UUID userId, UUID viewerId) {
            calls.incrementAndGet();
            return userId.equals(knownUser) ? Optional.of("Alice") : Optional.empty();
        }
    }

    @Test
    void aNameIsAskedOnceAndThenRemembered() {
        CountingUserClient client = new CountingUserClient();
        NameLookup lookup = new NameLookup(client, 5);

        for (int i = 0; i < 10; i++) {
            assertThat(lookup.displayName(client.knownUser, UUID.randomUUID())).isEqualTo("Alice");
        }
        assertThat(client.calls).hasValue(1);
    }

    @Test
    void anUnknownPersonIsSomeoneAndAskedAgainNextTime() {
        CountingUserClient client = new CountingUserClient();
        NameLookup lookup = new NameLookup(client, 5);
        UUID stranger = UUID.randomUUID();

        assertThat(lookup.displayName(stranger, UUID.randomUUID())).isEqualTo("Someone");
        assertThat(lookup.displayName(stranger, UUID.randomUUID())).isEqualTo("Someone");
        assertThat(client.calls).hasValue(2);
    }

    @Test
    void aZeroMinuteCacheAsksEveryTime() {
        CountingUserClient client = new CountingUserClient();
        NameLookup lookup = new NameLookup(client, 0);
        lookup.displayName(client.knownUser, UUID.randomUUID());
        lookup.displayName(client.knownUser, UUID.randomUUID());
        assertThat(client.calls).hasValue(2);
    }
}
