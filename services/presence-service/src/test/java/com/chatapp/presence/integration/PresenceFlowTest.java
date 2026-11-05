package com.chatapp.presence.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.chatapp.presence.repository.PresenceRepository;
import com.chatapp.presence.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** The heartbeat flow against a real Redis (default time-to-live: 60 seconds). */
class PresenceFlowTest extends AbstractIntegrationTest {

    @AfterEach
    void resetFakes() {
        USER_SERVICE.reset();
    }

    private Instant lastSeen(MvcResult result) throws Exception {
        String value = read(result, "$.data.lastSeenAt");
        return value == null ? null : Instant.parse(value);
    }

    // ------------------------------------------------------------------ online / offline

    @Test
    void aHeartbeatMakesTheUserOnline() throws Exception {
        Device alice = Device.newUser();
        Device bob = Device.newUser();
        Instant before = Instant.now().minusSeconds(2);

        heartbeat(alice);

        MvcResult asBob = presenceOf(bob, alice.userId());
        assertStatus(asBob, 200);
        assertThat((Boolean) read(asBob, "$.data.visible")).isTrue();
        assertThat((Boolean) read(asBob, "$.data.online")).isTrue();
        assertThat(lastSeen(asBob)).isBetween(before, Instant.now().plusSeconds(2));
        assertThat(read(asBob, "$.data.userId").toString()).isEqualTo(alice.userId().toString());
    }

    @Test
    void aUserThatWasNeverSeenIsOfflineWithoutLastSeen() throws Exception {
        MvcResult result = presenceOf(Device.newUser(), UUID.randomUUID());

        assertStatus(result, 200);
        assertThat((Boolean) read(result, "$.data.visible")).isTrue();
        assertThat((Boolean) read(result, "$.data.online")).isFalse();
        assertThat(lastSeen(result)).isNull();
    }

    @Test
    void goingOfflineMakesTheUserOfflineAndKeepsTheLastSeenTime() throws Exception {
        Device alice = Device.newUser();
        Device bob = Device.newUser();
        heartbeat(alice);

        goOffline(alice);

        MvcResult result = presenceOf(bob, alice.userId());
        assertThat((Boolean) read(result, "$.data.online")).isFalse();
        assertThat(lastSeen(result)).isBetween(Instant.now().minusSeconds(5), Instant.now().plusSeconds(2));
    }

    @Test
    void oneDeviceGoingOfflineDoesNotMakeTheUserOfflineWhileAnotherIsAlive() throws Exception {
        Device phone = Device.newUser();
        Device laptop = phone.otherDevice();
        Device bob = Device.newUser();
        heartbeat(phone);
        heartbeat(laptop);

        goOffline(phone);
        assertThat((Boolean) read(presenceOf(bob, phone.userId()), "$.data.online")).as("laptop is still alive").isTrue();

        goOffline(laptop);
        assertThat((Boolean) read(presenceOf(bob, phone.userId()), "$.data.online")).as("both devices are gone").isFalse();
        assertThat(lastSeen(presenceOf(bob, phone.userId()))).isNotNull();
    }

    @Test
    void goingOfflineWithADeviceThatWasNeverSeenIsHarmless() throws Exception {
        Device alice = Device.newUser();
        heartbeat(alice);

        goOffline(alice.otherDevice());   // another, unknown device of the same user

        assertThat((Boolean) read(presenceOf(Device.newUser(), alice.userId()), "$.data.online")).isTrue();
    }

    @Test
    void heartbeatsOfTheSameDeviceAreIdempotent() throws Exception {
        Device alice = Device.newUser();

        for (int i = 0; i < 5; i++) {
            heartbeat(alice);
        }

        assertThat(redis.opsForZSet().zCard(PresenceRepository.devicesKey(alice.userId()))).isEqualTo(1);
        assertThat((Boolean) read(presenceOf(Device.newUser(), alice.userId()), "$.data.online")).isTrue();
    }

    @Test
    void eachDeviceIsItsOwnMemberOfTheSet() throws Exception {
        Device phone = Device.newUser();
        Device laptop = phone.otherDevice();

        heartbeat(phone);
        heartbeat(laptop);

        assertThat(redis.opsForZSet().zCard(PresenceRepository.devicesKey(phone.userId()))).isEqualTo(2);
        assertThat(redis.opsForZSet().range(PresenceRepository.devicesKey(phone.userId()), 0, -1))
                .containsExactlyInAnyOrder(phone.sessionId().toString(), laptop.sessionId().toString());
    }

    @Test
    void theRedisKeysExpireByThemselves() throws Exception {
        Device alice = Device.newUser();
        heartbeat(alice);

        Long devicesTtl = redis.getExpire(PresenceRepository.devicesKey(alice.userId()));
        Long lastActiveTtl = redis.getExpire(PresenceRepository.lastActiveKey(alice.userId()));

        assertThat(devicesTtl).isBetween(1L, 75L);                                   // 60 s + 10 s margin
        assertThat(lastActiveTtl).isBetween(Duration.ofDays(29).toSeconds(), Duration.ofDays(30).toSeconds());
    }

    @Test
    void manyParallelHeartbeatsDoNotCorruptTheState() throws Exception {
        Device alice = Device.newUser();
        List<Device> devices = IntStream.range(0, 100).mapToObj(i -> alice.otherDevice()).toList();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<CompletableFuture<Void>> jobs = new ArrayList<>();
            for (Device device : devices) {
                jobs.add(CompletableFuture.runAsync(() -> {
                    try {
                        heartbeat(device);
                        heartbeat(device);     // twice: the same device must still count once
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, pool));
            }
            CompletableFuture.allOf(jobs.toArray(new CompletableFuture[0])).get();
        } finally {
            pool.shutdown();
        }

        assertThat(redis.opsForZSet().zCard(PresenceRepository.devicesKey(alice.userId()))).isEqualTo(100);
        assertThat((Boolean) read(presenceOf(Device.newUser(), alice.userId()), "$.data.online")).isTrue();
        assertThat(redis.opsForValue().get(PresenceRepository.lastActiveKey(alice.userId()))).isNotNull();
    }

    // ------------------------------------------------------------------------ privacy

    @Test
    void whenTheViewerMayNotSeeTheTargetEverythingIsNull() throws Exception {
        Device alice = Device.newUser();
        Device bob = Device.newUser();
        heartbeat(alice);
        USER_SERVICE.hide(bob.userId(), alice.userId());

        MvcResult result = presenceOf(bob, alice.userId());

        assertStatus(result, 200);
        assertThat((Boolean) read(result, "$.data.visible")).isFalse();
        assertThat((Object) read(result, "$.data.online")).isNull();
        assertThat((Object) read(result, "$.data.lastSeenAt")).isNull();
        // nothing that reveals the status, not even by accident
        assertThat(result.getResponse().getContentAsString()).doesNotContain("true,\"lastSeenAt\"");
    }

    @Test
    void privacyIsPerViewer() throws Exception {
        Device alice = Device.newUser();
        Device bob = Device.newUser();
        Device carol = Device.newUser();
        heartbeat(alice);
        USER_SERVICE.hide(bob.userId(), alice.userId());

        assertThat((Boolean) read(presenceOf(bob, alice.userId()), "$.data.visible")).isFalse();
        assertThat((Boolean) read(presenceOf(carol, alice.userId()), "$.data.visible")).isTrue();
    }

    @Test
    void youAlwaysSeeYourselfEvenIfUserServiceIsDown() throws Exception {
        Device alice = Device.newUser();
        heartbeat(alice);
        USER_SERVICE.down = true;
        int callsBefore = USER_SERVICE.calls.get();

        MvcResult result = presenceOf(alice, alice.userId());

        assertStatus(result, 200);
        assertThat((Boolean) read(result, "$.data.visible")).isTrue();
        assertThat((Boolean) read(result, "$.data.online")).isTrue();
        assertThat(USER_SERVICE.calls.get()).as("no privacy question about yourself").isEqualTo(callsBefore);
    }

    @Test
    void whenUserServiceIsDownTheStatusOfOthersIsNotShown() throws Exception {
        Device alice = Device.newUser();
        Device bob = Device.newUser();
        heartbeat(alice);
        USER_SERVICE.down = true;

        MvcResult result = presenceOf(bob, alice.userId());

        assertStatus(result, 503);
        assertThat((String) read(result, "$.data.errorCode")).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("online");
    }

    // ------------------------------------------------------------------ security and input

    @Test
    void everyEndpointNeedsAToken() throws Exception {
        assertStatus(post("/api/presence/heartbeat", null), 401);
        assertStatus(post("/api/presence/offline", null), 401);
        assertStatus(presenceOf(null, UUID.randomUUID()), 401);
    }

    @Test
    void aBadUserIdIs400() throws Exception {
        assertStatus(get("/api/presence/not-a-uuid", Device.newUser()), 400);
    }

    @Test
    void aTokenWithoutADeviceIsRefused() throws Exception {
        String token = com.chatapp.presence.support.TestJwt.tokenWithoutDevice(UUID.randomUUID());
        MvcResult result = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/presence/heartbeat")
                                .header("Authorization", "Bearer " + token))
                .andReturn();
        assertStatus(result, 401);
    }

    // ------------------------------------------------------------ internal online lookup

    @Test
    void internalLookupReturnsOnlyUsersWithALiveDevice() throws Exception {
        Device online1 = Device.newUser();
        Device online2 = Device.newUser();
        Device wasOnline = Device.newUser();
        UUID never = UUID.randomUUID();
        heartbeat(online1);
        heartbeat(online2);
        heartbeat(wasOnline);
        goOffline(wasOnline);

        String ids = List.of(online1.userId(), wasOnline.userId(), never, online2.userId()).stream()
                .map(UUID::toString).collect(Collectors.joining(","));
        MvcResult result = internalOnline(ids);

        assertStatus(result, 200);
        List<String> found = read(result, "$.data.onlineUserIds");
        assertThat(found).containsExactlyInAnyOrder(online1.userId().toString(), online2.userId().toString());
    }

    @Test
    void internalLookupIgnoresPrivacy() throws Exception {
        Device alice = Device.newUser();
        heartbeat(alice);
        USER_SERVICE.down = true;   // privacy is not even asked

        List<String> found = read(internalOnline(alice.userId().toString()), "$.data.onlineUserIds");

        assertThat(found).containsExactly(alice.userId().toString());
    }

    @Test
    void internalLookupAcceptsExactly200IdsAndRefusesMore() throws Exception {
        String ids200 = IntStream.range(0, 200).mapToObj(i -> UUID.randomUUID().toString()).collect(Collectors.joining(","));
        String ids201 = ids200 + "," + UUID.randomUUID();

        assertStatus(internalOnline(ids200), 200);
        assertStatus(internalOnline(ids201), 400);
    }

    @Test
    void internalLookupRefusesBadInput() throws Exception {
        assertStatus(internalOnline(""), 400);
        assertStatus(internalOnline("not-a-uuid"), 400);
        assertStatus(internalOnline(UUID.randomUUID() + ",,"), 400);
        assertStatus(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/internal/presence/online").header("X-Internal-Token", com.chatapp.presence.support.TestJwt.INTERNAL_TOKEN))
                .andReturn(), 400);
    }

    @Test
    void internalLookupNeedsTheInternalTokenNotAUserToken() throws Exception {
        Device alice = Device.newUser();
        String url = "/internal/presence/online";
        var noToken = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url)
                .param("userIds", alice.userId().toString())).andReturn();
        var userToken = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url)
                .param("userIds", alice.userId().toString()).header("Authorization", "Bearer " + alice.token())).andReturn();
        var wrongToken = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url)
                .param("userIds", alice.userId().toString()).header("X-Internal-Token", "wrong-token-wrong-token-wrong-1234")).andReturn();

        assertStatus(noToken, 401);
        assertStatus(userToken, 401);
        assertStatus(wrongToken, 401);
    }

    @Test
    void theInternalTokenDoesNotOpenTheUserApi() throws Exception {
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/presence/heartbeat").header("X-Internal-Token", com.chatapp.presence.support.TestJwt.INTERNAL_TOKEN)).andReturn();
        assertStatus(result, 401);
    }
}
