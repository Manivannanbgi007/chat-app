package com.chatapp.call.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.chatapp.call.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Several requests at the very same moment: the database must keep every person in at most one call. */
class CallConcurrencyTest extends AbstractIntegrationTest {

    private ExecutorService pool;

    @BeforeEach
    void startPool() {
        pool = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    /** Runs all tasks at the same moment and returns their results in order. */
    private List<MvcResult> together(List<Callable<MvcResult>> tasks) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (Callable<MvcResult> task : tasks) {
            futures.add(pool.submit(() -> {
                go.await();
                return task.call();
            }));
        }
        go.countDown();
        List<MvcResult> results = new ArrayList<>();
        for (Future<MvcResult> future : futures) {
            results.add(future.get());
        }
        return results;
    }

    @Test
    void eightPeopleCallingOnePersonAtOnceMakeExactlyOneCallRing() throws Exception {
        Person bob = newPerson("Bob");
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Person caller = newPerson("Caller" + i);
            calls.add(() -> startCall(caller, bob, "AUDIO"));
        }

        List<MvcResult> results = together(calls);

        List<String> statuses = new ArrayList<>();
        for (MvcResult result : results) {
            assertStatus(result, 201);
            statuses.add(field(result, "$.data.call.status"));
        }
        assertThat(statuses).filteredOn("RINGING"::equals).hasSize(1);
        assertThat(statuses).filteredOn("BUSY"::equals).hasSize(7);
        assertThat(slotsOf(bob)).isEqualTo(1);
        Integer slotRows = jdbc.queryForObject("SELECT COUNT(*) FROM call_slots s JOIN calls c ON c.id = s.call_id "
                + "WHERE c.callee_id = ?", Integer.class, bob.userId().toString());
        assertThat(slotRows).as("only the ringing call holds slots (caller + bob)").isEqualTo(2);
    }

    @Test
    void twoPeopleCallingEachOtherAtTheSameMomentGetOneCall() throws Exception {
        for (int round = 0; round < 5; round++) {
            Person alice = newPerson("Alice");
            Person bob = newPerson("Bob");

            List<MvcResult> results = together(List.of(
                    () -> startCall(alice, bob, "VIDEO"),
                    () -> startCall(bob, alice, "VIDEO")));

            List<String> outcomes = new ArrayList<>();
            for (MvcResult result : results) {
                int status = result.getResponse().getStatus();
                outcomes.add(status == 201 ? field(result, "$.data.call.status") : field(result, "$.data.errorCode"));
            }
            assertThat(outcomes).as("round " + round).filteredOn("RINGING"::equals).hasSize(1);
            assertThat(outcomes).as("round " + round).allMatch(o -> o.equals("RINGING") || o.equals("BUSY")
                    || o.equals("ALREADY_IN_CALL"));
            assertThat(slotsOf(alice)).isEqualTo(1);
            assertThat(slotsOf(bob)).isEqualTo(1);
        }
    }

    @Test
    void acceptRacingTheCallersHangUpHasExactlyOneWinner() throws Exception {
        readCallEvents();
        for (int round = 0; round < 10; round++) {
            Person alice = newPerson("Alice");
            Person bob = newPerson("Bob");
            UUID callId = ringing(alice, bob);

            List<MvcResult> results = together(List.of(
                    () -> action(callId, "accept", bob),
                    () -> action(callId, "end", alice)));

            MvcResult accept = results.get(0);
            assertStatus(results.get(1), 200);
            String finalStatus = statusInDb(callId);
            if (accept.getResponse().getStatus() == 200) {
                // the callee was first: the call was answered and then the caller hung up
                assertThat(finalStatus).as("round " + round).isEqualTo("ENDED");
            } else {
                // the caller was first: cancelled before anybody answered
                assertError(accept, 409, "CALL_NOT_RINGING");
                assertThat(finalStatus).as("round " + round).isEqualTo("MISSED");
            }
            assertThat(slotsOf(alice) + slotsOf(bob)).isZero();
            awaitCallEnded(callId);
            assertThat(callEndedEvents(callId)).hasSize(1);
        }
    }
}
