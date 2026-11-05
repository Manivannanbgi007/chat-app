package com.chatapp.presence.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import com.chatapp.presence.repository.PresenceRepository;
import com.chatapp.presence.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A device that stops sending heartbeats expires by itself. A very short time-to-live (2 seconds) makes
 * that testable: this class has its own application context with chatapp.presence.ttl-seconds=2.
 */
@SpringBootTest(properties = "chatapp.presence.ttl-seconds=2")
class PresenceExpiryTest extends AbstractIntegrationTest {

    private boolean online(Device viewer, java.util.UUID target) throws Exception {
        return read(presenceOf(viewer, target), "$.data.online");
    }

    @Test
    void aDeviceWithoutHeartbeatsTurnsOfflineAndKeepsTheTimeOfItsLastHeartbeat() throws Exception {
        Device alice = Device.newUser();
        Device bob = Device.newUser();
        Instant before = Instant.now();
        heartbeat(alice);
        Instant after = Instant.now();
        assertThat(online(bob, alice.userId())).isTrue();

        Thread.sleep(2_600);

        MvcResult result = presenceOf(bob, alice.userId());
        assertThat((Boolean) read(result, "$.data.online")).as("expired without any offline call").isFalse();
        Instant lastSeen = Instant.parse(read(result, "$.data.lastSeenAt"));
        assertThat(lastSeen).isBetween(before.minusMillis(50), after.plusMillis(50));   // about the last heartbeat
    }

    @Test
    void aNewHeartbeatKeepsTheDeviceAlive() throws Exception {
        Device alice = Device.newUser();
        Device bob = Device.newUser();

        heartbeat(alice);
        Thread.sleep(1_200);
        heartbeat(alice);
        Thread.sleep(1_200);   // 2.4 s after the first heartbeat, but only 1.2 s after the second

        assertThat(online(bob, alice.userId())).isTrue();
    }

    @Test
    void eachDeviceExpiresOnItsOwn() throws Exception {
        Device phone = Device.newUser();
        Device laptop = phone.otherDevice();
        Device bob = Device.newUser();

        heartbeat(phone);               // alive until about t = 2.0
        Thread.sleep(1_500);
        heartbeat(laptop);              // alive until about t = 3.5
        Thread.sleep(1_100);            // t = 2.6: the phone expired, the laptop did not
        assertThat(online(bob, phone.userId())).as("the laptop keeps the user online").isTrue();

        Thread.sleep(1_500);            // t = 4.1: both expired
        assertThat(online(bob, phone.userId())).isFalse();
    }

    @Test
    void expiredDevicesAreCleanedOutOfTheSetByTheNextHeartbeat() throws Exception {
        Device phone = Device.newUser();
        Device laptop = phone.otherDevice();
        heartbeat(phone);
        assertThat(redis.opsForZSet().zCard(PresenceRepository.devicesKey(phone.userId()))).isEqualTo(1);

        Thread.sleep(2_600);
        heartbeat(laptop);

        assertThat(redis.opsForZSet().range(PresenceRepository.devicesKey(phone.userId()), 0, -1))
                .containsExactly(laptop.sessionId().toString());
    }

    @Test
    void theInternalLookupAlsoSeesTheExpiry() throws Exception {
        Device alice = Device.newUser();
        heartbeat(alice);
        assertThat((java.util.List<String>) read(internalOnline(alice.userId().toString()), "$.data.onlineUserIds"))
                .containsExactly(alice.userId().toString());

        Thread.sleep(2_600);

        assertThat((java.util.List<String>) read(internalOnline(alice.userId().toString()), "$.data.onlineUserIds")).isEmpty();
    }
}
