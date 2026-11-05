package com.chatapp.presence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.chatapp.presence.config.PresenceProperties;
import org.junit.jupiter.api.Test;

/** The names of the Redis keys (they are a contract with DATABASE_DESIGN.md) and the setting checks. */
class PresenceKeysAndSettingsTest {

    private final UUID user = UUID.fromString("0192b5d2-7c1e-7a55-9d3a-2f6a1c0b9e11");

    @Test
    void devicesKeyFollowsTheDesign() {
        assertThat(PresenceRepository.devicesKey(user)).isEqualTo("presence:devices:0192b5d2-7c1e-7a55-9d3a-2f6a1c0b9e11");
    }

    @Test
    void lastActiveKeyFollowsTheDesign() {
        assertThat(PresenceRepository.lastActiveKey(user)).isEqualTo("presence:lastactive:0192b5d2-7c1e-7a55-9d3a-2f6a1c0b9e11");
    }

    @Test
    void keysOfDifferentUsersNeverClash() {
        assertThat(PresenceRepository.devicesKey(UUID.randomUUID())).isNotEqualTo(PresenceRepository.devicesKey(UUID.randomUUID()));
        assertThat(PresenceRepository.devicesKey(user)).isNotEqualTo(PresenceRepository.lastActiveKey(user));
    }

    @Test
    void theTimeToLiveMustBePositive() {
        assertThat(new PresenceProperties(60).ttlSeconds()).isEqualTo(60);
        assertThatThrownBy(() -> new PresenceProperties(0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PresenceProperties(-5)).isInstanceOf(IllegalStateException.class);
    }
}
