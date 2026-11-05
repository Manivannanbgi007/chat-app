package com.chatapp.call.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chatapp.call.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** The happy path of a call: ring, accept, signal, hang up, and reading calls back. */
class CallFlowTest extends AbstractIntegrationTest {

    @BeforeEach
    void listen() {
        listenToLiveEvents();
    }

    @Test
    void startingACallRingsOnlyTheCalleeAndGivesTheCallerIceServers() throws Exception {
        Person alice = newPerson("Alice");
        Person bob = newPerson("Bob");

        MvcResult result = startCall(alice, bob, "VIDEO");

        assertStatus(result, 201);
        UUID callId = UUID.fromString(field(result, "$.data.call.id"));
        assertThat(field(result, "$.data.call.status")).isEqualTo("RINGING");
        assertThat(field(result, "$.data.call.type")).isEqualTo("VIDEO");
        assertThat(field(result, "$.data.call.direction")).isEqualTo("OUTGOING");
        assertThat(field(result, "$.data.call.endReason")).isNull();
        assertThat(field(result, "$.data.call.callerId")).isEqualTo(alice.userId().toString());
        assertThat(field(result, "$.data.call.calleeId")).isEqualTo(bob.userId().toString());
        assertThat(field(result, "$.data.call.otherUser.displayName")).isEqualTo(bob.name());
        assertThat(field(result, "$.data.call.answeredAt")).isNull();
        assertThat(field(result, "$.data.call.durationSeconds")).isEqualTo("0");

        // STUN first (no password), then TURN with a password made for alice
        assertThat(field(result, "$.data.iceServers[0].urls[0]")).isEqualTo("stun:localhost:3478");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("\"username\":null");
        List<String> turnUrls = read(result, "$.data.iceServers[1].urls");
        assertThat(turnUrls).containsExactly("turn:localhost:3478?transport=udp", "turn:localhost:3478?transport=tcp");
        assertThat(field(result, "$.data.iceServers[1].username")).endsWith(":" + alice.userId());
        assertThat(field(result, "$.data.iceServers[1].credential")).isNotBlank();

        LiveEvent incoming = awaitLive(bob, callId, "CALL_INCOMING");
        assertThat(incoming.destination()).isEqualTo("/queue/calls");
        assertThat(incoming.userIds()).containsExactly(bob.userId());
        assertThat(incoming.data().get("direction").asText()).isEqualTo("INCOMING");
        assertThat(incoming.data().get("status").asText()).isEqualTo("RINGING");
        assertThat(incoming.data().get("otherUser").get("displayName").asText()).isEqualTo(alice.name());
        pause(300);
        assertThat(liveEventsFor(alice, callId)).as("nothing rings for the caller").isEmpty();
    }

    @Test
    void acceptSignalAndHangUpReachTheRightPeopleAndDevices() throws Exception {
        Person alice = newPerson("Alice");
        Person bob = newPerson("Bob");
        UUID callId = ringing(alice, bob);

        MvcResult accept = action(callId, "accept", bob);
        assertStatus(accept, 200);
        assertThat(field(accept, "$.data.call.status")).isEqualTo("ACCEPTED");
        assertThat(field(accept, "$.data.call.direction")).isEqualTo("INCOMING");
        assertThat(field(accept, "$.data.call.answeredAt")).isNotNull();
        assertThat(field(accept, "$.data.iceServers[1].username")).endsWith(":" + bob.userId());

        // both sides hear about it, with the device that answered
        LiveEvent toAlice = awaitLive(alice, callId, "CALL_ACCEPTED");
        LiveEvent toBob = awaitLive(bob, callId, "CALL_ACCEPTED");
        assertThat(toAlice.data().get("calleeDeviceId").asText()).isEqualTo(bob.deviceId().toString());
        assertThat(toAlice.data().get("call").get("direction").asText()).isEqualTo("OUTGOING");
        assertThat(toBob.data().get("call").get("direction").asText()).isEqualTo("INCOMING");

        // the caller's device sends the offer, it arrives at bob's call device
        assertStatus(post("/api/calls/" + callId + "/signal", Map.of("type", "OFFER", "sdp", "v=0 offer"), alice), 200);
        LiveEvent offer = awaitLive(bob, callId, "CALL_SIGNAL");
        assertThat(offer.userIds()).containsExactly(bob.userId());
        assertThat(offer.data().get("signalType").asText()).isEqualTo("OFFER");
        assertThat(offer.data().get("sdp").asText()).isEqualTo("v=0 offer");
        assertThat(offer.data().get("fromUserId").asText()).isEqualTo(alice.userId().toString());
        assertThat(offer.data().get("toDeviceId").asText()).isEqualTo(bob.deviceId().toString());

        // bob answers, and sends ICE candidates; both arrive at alice's call device
        assertStatus(post("/api/calls/" + callId + "/signal", Map.of("type", "ANSWER", "sdp", "v=0 answer"), bob), 200);
        Map<String, Object> candidate = Map.of("candidate", "candidate:1 1 udp 2122260223 10.0.0.2 54321 typ host",
                "sdpMid", "0", "sdpMLineIndex", 0);
        assertStatus(post("/api/calls/" + callId + "/signal", Map.of("type", "ICE", "candidates", List.of(candidate)), bob), 200);
        eventually(() -> liveEventsFor(alice, callId).stream().filter(e -> e.type().equals("CALL_SIGNAL")).count() == 2,
                "answer and ICE for alice");
        List<LiveEvent> forAlice = liveEventsFor(alice, callId).stream().filter(e -> e.type().equals("CALL_SIGNAL")).toList();
        assertThat(forAlice).allSatisfy(e -> assertThat(e.data().get("toDeviceId").asText()).isEqualTo(alice.deviceId().toString()));
        assertThat(forAlice.get(0).data().get("signalType").asText()).isEqualTo("ANSWER");
        assertThat(forAlice.get(1).data().get("candidates").get(0).get("candidate").asText()).contains("typ host");
        assertThat(forAlice.get(1).data().get("sdp").isNull()).isTrue();

        assertStatus(action(callId, "ping", alice), 200);
        assertStatus(action(callId, "ping", bob), 200);

        // pretend the call lasted 65 seconds, then alice hangs up
        ageCall(callId, "answered_at", 65);
        MvcResult end = action(callId, "end", alice);
        assertStatus(end, 200);
        assertThat(field(end, "$.data.status")).isEqualTo("ENDED");
        assertThat(field(end, "$.data.endReason")).isEqualTo("HANGUP");
        assertThat(Long.parseLong(field(end, "$.data.durationSeconds"))).isBetween(65L, 70L);
        assertThat(awaitLive(alice, callId, "CALL_ENDED").data().get("status").asText()).isEqualTo("ENDED");
        assertThat(awaitLive(bob, callId, "CALL_ENDED").data().get("endReason").asText()).isEqualTo("HANGUP");

        // both are free again: the same two can call each other once more
        assertThat(slotsOf(alice)).isZero();
        assertThat(slotsOf(bob)).isZero();
        ringing(bob, alice);
    }

    @Test
    void activeShowsTheCallWhileItRingsOrRunsAndNothingAfterwards() throws Exception {
        Person alice = newPerson("Alice");
        Person bob = newPerson("Bob");
        assertThat(field(get("/api/calls/active", alice), "$.data")).isNull();

        UUID callId = ringing(alice, bob);
        MvcResult bobsActive = get("/api/calls/active", bob);
        assertStatus(bobsActive, 200);
        assertThat(field(bobsActive, "$.data.id")).isEqualTo(callId.toString());
        assertThat(field(bobsActive, "$.data.status")).isEqualTo("RINGING");
        assertThat(field(bobsActive, "$.data.direction")).isEqualTo("INCOMING");
        assertThat(field(get("/api/calls/active", alice), "$.data.direction")).isEqualTo("OUTGOING");

        action(callId, "accept", bob);
        assertThat(field(get("/api/calls/active", alice.otherDevice()), "$.data.status")).isEqualTo("ACCEPTED");

        action(callId, "end", bob);
        assertThat(field(get("/api/calls/active", alice), "$.data")).isNull();
        assertThat(field(get("/api/calls/active", bob), "$.data")).isNull();
    }

    @Test
    void aCallCanOnlyBeReadByItsTwoParticipants() throws Exception {
        Person alice = newPerson("Alice");
        Person bob = newPerson("Bob");
        Person carol = newPerson("Carol");
        UUID callId = ringing(alice, bob);

        MvcResult asBob = get("/api/calls/" + callId, bob);
        assertStatus(asBob, 200);
        assertThat(field(asBob, "$.data.otherUser.displayName")).isEqualTo(alice.name());

        MvcResult asCarol = get("/api/calls/" + callId, carol);
        MvcResult unknown = get("/api/calls/" + UUID.randomUUID(), carol);
        assertStatus(asCarol, 404);
        assertStatus(unknown, 404);
        assertThat(field(asCarol, "$.message")).isEqualTo(field(unknown, "$.message"));
        assertStatus(get("/api/calls/" + callId, null), 401);
    }

    @Test
    void historyListsEveryCallNewestFirstFromEachPersonsPointOfView() throws Exception {
        Person alice = newPerson("Alice");
        Person bob = newPerson("Bob");
        Person carol = newPerson("Carol");

        UUID first = ringing(alice, bob);
        action(first, "reject", bob);
        UUID second = ringing(carol, alice);
        action(second, "end", carol);
        UUID third = accepted(alice, carol);
        action(third, "end", carol);

        MvcResult page0 = get("/api/calls/history?page=0&size=2", alice);
        assertStatus(page0, 200);
        List<String> ids = read(page0, "$.data.items[*].id");
        assertThat(ids).containsExactly(third.toString(), second.toString());
        assertThat(field(page0, "$.data.totalItems")).isEqualTo("3");
        assertThat(field(page0, "$.data.hasMore")).isEqualTo("true");
        assertThat(field(page0, "$.data.items[0].direction")).isEqualTo("OUTGOING");
        assertThat(field(page0, "$.data.items[0].status")).isEqualTo("ENDED");
        assertThat(field(page0, "$.data.items[1].direction")).isEqualTo("INCOMING");
        assertThat(field(page0, "$.data.items[1].status")).isEqualTo("MISSED");
        assertThat(field(page0, "$.data.items[1].otherUser.displayName")).isEqualTo(carol.name());

        MvcResult page1 = get("/api/calls/history?page=1&size=2", alice);
        assertThat((List<String>) read(page1, "$.data.items[*].id")).containsExactly(first.toString());
        assertThat(field(page1, "$.data.hasMore")).isEqualTo("false");
        assertThat(field(page1, "$.data.items[0].status")).isEqualTo("REJECTED");

        MvcResult bobs = get("/api/calls/history", bob);
        assertThat((List<String>) read(bobs, "$.data.items[*].id")).containsExactly(first.toString());
        assertThat(field(bobs, "$.data.items[0].direction")).isEqualTo("INCOMING");
        assertThat(field(bobs, "$.data.items[0].otherUser.displayName")).isEqualTo(alice.name());

        assertStatus(get("/api/calls/history?size=51", alice), 400);
        assertStatus(get("/api/calls/history?size=0", alice), 400);
        assertStatus(get("/api/calls/history?page=-1", alice), 400);
    }
}
