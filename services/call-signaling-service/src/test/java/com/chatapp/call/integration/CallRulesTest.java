package com.chatapp.call.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chatapp.call.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Every rule that can be broken, broken on purpose: who may call, busy, who may accept, devices, states. */
class CallRulesTest extends AbstractIntegrationTest {

    @BeforeEach
    void listen() {
        listenToLiveEvents();
    }

    @AfterEach
    void userServiceIsBack() {
        USERS.down = false;
    }

    // --------------------------------------------------------------- who may call

    @Test
    void youCannotCallYourselfAnUnknownUserOrSomeoneWhoBlockedYouOrWhomYouBlocked() throws Exception {
        Person alice = newPerson("Alice");
        Person bob = newPerson("Bob");
        Person carol = newPerson("Carol");

        assertStatus(startCall(alice, alice, "AUDIO"), 400);
        assertStatus(post("/api/calls", Map.of("calleeId", UUID.randomUUID(), "type", "AUDIO"), alice), 404);

        USERS.block(bob.userId(), alice.userId());                                   // bob blocked alice
        assertError(startCall(alice, bob, "AUDIO"), 403, "CALL_NOT_ALLOWED");
        assertError(startCall(bob, alice, "AUDIO"), 403, "CALL_NOT_ALLOWED");   // and the other way round
        USERS.block(alice.userId(), carol.userId());                                 // alice blocked carol
        assertError(startCall(carol, alice, "AUDIO"), 403, "CALL_NOT_ALLOWED");

        assertThat(slotsOf(alice)).isZero();
        assertStatus(post("/api/calls", Map.of("calleeId", bob.userId(), "type", "AUDIO"), null), 401);
    }

    @Test
    void whenUserServiceIsDownACallCannotStartButARunningOneCanStillEnd() throws Exception {
        Person alice = newPerson("Alice");
        Person bob = newPerson("Bob");
        UUID callId = accepted(alice, bob);

        USERS.down = true;
        assertError(startCall(alice, newPerson("Carol"), "AUDIO"), 503, "DEPENDENCY_UNAVAILABLE");
        MvcResult end = action(callId, "end", bob);
        assertStatus(end, 200);
        assertThat(field(end, "$.data.status")).isEqualTo("ENDED");
        assertThat(field(end, "$.data.otherUser.displayName")).isEqualTo("Unknown user");
    }

    // ------------------------------------------------------------------ busy

    @Test
    void aBusyCalleeGivesBusyAtOnceAndNothingRings() throws Exception {
        Person alice = newPerson("Alice");
        Person bob = newPerson("Bob");
        Person carol = newPerson("Carol");
        ringing(alice, bob);

        MvcResult busy = startCall(carol, bob, "AUDIO");

        assertStatus(busy, 201);
        UUID busyCall = UUID.fromString(field(busy, "$.data.call.id"));
        assertThat(field(busy, "$.data.call.status")).isEqualTo("BUSY");
        assertThat(field(busy, "$.data.call.endReason")).isEqualTo("BUSY");
        assertThat(field(busy, "$.data.call.endedAt")).isNotNull();
        assertThat((List<Object>) read(busy, "$.data.iceServers")).isEmpty();
        assertThat(slotsOf(carol)).as("a BUSY call keeps no slot").isZero();
        assertThat(slotsOf(bob)).isEqualTo(1);
        pause(300);
        assertThat(liveEventsFor(bob, busyCall)).as("nothing rings for a busy callee").isEmpty();

        // carol is free to call somebody else
        ringing(carol, newPerson("Dave"));
    }

    @Test
    void someoneInACallCannotStartAnotherOneOnAnyDevice() throws Exception {
        Person alice = newPerson("Alice");
        Person bob = newPerson("Bob");
        UUID callId = ringing(alice, bob);

        assertError(startCall(alice, newPerson("Carol"), "AUDIO"), 409, "ALREADY_IN_CALL");
        assertError(startCall(alice.otherDevice(), newPerson("Carol"), "AUDIO"), 409, "ALREADY_IN_CALL");
        // the callee of a ringing call is "in a call" too
        assertError(startCall(bob, newPerson("Carol"), "VIDEO"), 409, "ALREADY_IN_CALL");
        assertThat(statusInDb(callId)).isEqualTo("RINGING");
    }

    // --------------------------------------------------------- accept / reject / end

    @Test
    void onlyTheCalleeCanAcceptAndOnlyOnceEvenWithSeveralDevices() throws Exception {
        Person alice = newPerson("Alice");
        Person bob = newPerson("Bob");
        Person bobsPhone = bob.otherDevice();
        UUID callId = ringing(alice, bob);

        assertError(action(callId, "accept", alice), 403, "NOT_CALLEE");
        assertStatus(action(callId, "accept", newPerson("Carol")), 404);

        assertStatus(action(callId, "accept", bob), 200);
        assertError(action(callId, "accept", bobsPhone), 409, "CALL_NOT_RINGING");
        assertError(action(callId, "reject", bobsPhone), 409, "CALL_NOT_RINGING");
        assertThat(jdbc.queryForObject("SELECT callee_device_id FROM calls WHERE id = ?", String.class, callId.toString()))
                .isEqualTo(bob.deviceId().toString());
    }

    @Test
    void theCalleeCanDeclineAndThenBothAreFree() throws Exception {
        Person alice = newPerson("Alice");
        Person bob = newPerson("Bob");
        UUID callId = ringing(alice, bob);

        assertError(action(callId, "reject", alice), 403, "NOT_CALLEE");
        MvcResult reject = action(callId, "reject", bob);

        assertStatus(reject, 200);
        assertThat(field(reject, "$.data.status")).isEqualTo("REJECTED");
        assertThat(field(reject, "$.data.endReason")).isEqualTo("DECLINED");
        assertThat(awaitLive(alice, callId, "CALL_ENDED").data().get("status").asText()).isEqualTo("REJECTED");
        assertThat(slotsOf(alice) + slotsOf(bob)).isZero();
        assertError(action(callId, "accept", bob), 409, "CALL_NOT_RINGING");
    }

    @Test
    void hangingUpWhileRingingIsCancelForTheCallerAndDeclineForTheCallee() throws Exception {
        Person alice = newPerson("Alice");
        Person bob = newPerson("Bob");

        UUID cancelled = ringing(alice, bob);
        MvcResult byCaller = action(cancelled, "end", alice);
        assertThat(field(byCaller, "$.data.status")).isEqualTo("MISSED");
        assertThat(field(byCaller, "$.data.endReason")).isEqualTo("CANCELLED");
        assertThat(awaitLive(bob, cancelled, "CALL_ENDED").data().get("status").asText()).isEqualTo("MISSED");

        UUID declined = ringing(alice, bob);
        MvcResult byCallee = action(declined, "end", bob);
        assertThat(field(byCallee, "$.data.status")).isEqualTo("REJECTED");
        assertThat(field(byCallee, "$.data.endReason")).isEqualTo("DECLINED");
    }

    @Test
    void endingTwiceIsHarmlessAndAnnouncedOnlyOnce() throws Exception {
        Person alice = newPerson("Alice");
        Person bob = newPerson("Bob");
        readCallEvents();
        UUID callId = accepted(alice, bob);

        MvcResult first = action(callId, "end", alice);
        MvcResult again = action(callId, "end", bob);
        MvcResult third = action(callId, "end", alice.otherDevice());

        assertStatus(again, 200);
        assertStatus(third, 200);
        assertThat(field(again, "$.data.status")).isEqualTo("ENDED");
        assertThat(field(again, "$.data.endedAt")).isEqualTo(field(first, "$.data.endedAt"));
        awaitLive(bob, callId, "CALL_ENDED");
        awaitCallEnded(callId);
        pause(800);
        assertThat(liveEventsFor(bob, callId).stream().filter(e -> e.type().equals("CALL_ENDED"))).hasSize(1);
        assertThat(callEndedEvents(callId)).hasSize(1);
        assertStatus(action(callId, "end", newPerson("Carol")), 404);
    }

    // ------------------------------------------------------------ signal and ping

    @Test
    void signalsOnlyFlowBetweenTheTwoCallDevicesOfAnAcceptedCall() throws Exception {
        Person alice = newPerson("Alice");
        Person bob = newPerson("Bob");
        Map<String, Object> offer = Map.of("type", "OFFER", "sdp", "v=0");
        UUID callId = ringing(alice, bob);

        assertError(post("/api/calls/" + callId + "/signal", offer, alice), 409, "CALL_NOT_ACTIVE");
        assertError(action(callId, "ping", alice), 409, "CALL_NOT_ACTIVE");

        action(callId, "accept", bob);
        assertError(post("/api/calls/" + callId + "/signal", offer, alice.otherDevice()), 403, "NOT_CALL_DEVICE");
        assertError(post("/api/calls/" + callId + "/signal", Map.of("type", "ANSWER", "sdp", "v=0"), bob.otherDevice()),
                403, "NOT_CALL_DEVICE");
        assertError(action(callId, "ping", bob.otherDevice()), 403, "NOT_CALL_DEVICE");
        assertStatus(post("/api/calls/" + callId + "/signal", offer, newPerson("Carol")), 404);
        assertStatus(post("/api/calls/" + callId + "/signal", offer, alice), 200);

        action(callId, "end", alice);
        assertError(post("/api/calls/" + callId + "/signal", offer, alice), 409, "CALL_NOT_ACTIVE");
        assertError(action(callId, "ping", bob), 409, "CALL_NOT_ACTIVE");
    }

    @Test
    void badSignalBodiesAreRejectedWithTheFieldThatIsWrong() throws Exception {
        Person alice = newPerson("Alice");
        Person bob = newPerson("Bob");
        UUID callId = accepted(alice, bob);
        String path = "/api/calls/" + callId + "/signal";

        MvcResult noSdp = post(path, Map.of("type", "OFFER"), alice);
        assertStatus(noSdp, 400);
        assertThat(field(noSdp, "$.data.sdp")).isNotBlank();
        MvcResult noCandidates = post(path, Map.of("type", "ICE", "candidates", List.of()), alice);
        assertStatus(noCandidates, 400);
        assertThat(field(noCandidates, "$.data.candidates")).isNotBlank();
        assertStatus(post(path, Map.of("type", "OFFER", "sdp", "x".repeat(20001)), alice), 400);
        assertStatus(post(path, Map.of("type", "HELLO", "sdp", "x"), alice), 400);
    }
}
