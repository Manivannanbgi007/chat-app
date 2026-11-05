package com.chatapp.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chatapp.notification.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** call.events in (Phase 7), "Missed voice / video call" notifications out. */
class CallNotificationTest extends AbstractIntegrationTest {

    private static final String TOPIC = "call.events";

    private UUID caller;
    private UUID callee;

    @BeforeEach
    void setUp() {
        caller = newId();
        callee = newId();
        PEERS.setName(caller, "Asha Rao");
    }

    @AfterEach
    void restorePeers() {
        PEERS.setOffline(callee);
        PEERS.userDown = false;
    }

    @Test
    void aMissedVoiceCallNotifiesTheCalleeOnly() {
        awaitHandled(callEnded(caller, callee, "AUDIO", "MISSED"));

        assertThat(notificationCount(caller)).isZero();
        List<Map<String, Object>> rows = notificationsOf(callee);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("type", "MISSED_CALL")
                .containsEntry("title", "Asha Rao")
                .containsEntry("body", "Missed voice call")
                .containsEntry("item_count", 1)
                .containsEntry("is_read", false);
        assertThat(rows.get(0).get("conversation_id")).isNull();
    }

    @Test
    void aBusyVideoCallIsAMissedVideoCall() {
        awaitHandled(callEnded(caller, callee, "VIDEO", "BUSY"));

        assertThat(notificationsOf(callee)).hasSize(1);
        assertThat(notificationsOf(callee).get(0))
                .containsEntry("type", "MISSED_CALL").containsEntry("body", "Missed video call");
        assertThat(notificationCount(caller)).isZero();
    }

    @Test
    void answeredAndRejectedCallsNotifyNobody() {
        // the same Kafka key for all three, so they land on one partition and are handled in order
        UUID key = newId();
        publish(TOPIC, key, event(newId(), "CallEndedEvent", key, callEndedPayload(key, caller, callee, "AUDIO", "ANSWERED")));
        publish(TOPIC, key, event(newId(), "CallEndedEvent", key, callEndedPayload(key, caller, callee, "VIDEO", "REJECTED")));
        flush(TOPIC, key);

        assertThat(notificationCount(callee)).isZero();
        assertThat(notificationCount(caller)).isZero();
    }

    @Test
    void callsEnabledFalseSkipsMissedCalls() throws Exception {
        setPreferences(callee, true, true, true, false);
        UUID eventId = callEnded(caller, callee, "AUDIO", "MISSED");
        awaitHandled(eventId);
        assertThat(notificationCount(callee)).isZero();
    }

    @Test
    void missedCallsAreNeverMerged() {
        awaitHandled(callEnded(caller, callee, "AUDIO", "MISSED"));
        awaitHandled(callEnded(caller, callee, "VIDEO", "MISSED"));

        List<Map<String, Object>> rows = notificationsOf(callee);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> assertThat(row).containsEntry("item_count", 1));
    }

    @Test
    void theSameEventTwiceCreatesOneNotification() {
        UUID eventId = newId();
        UUID callId = newId();
        String json = event(eventId, "CallEndedEvent", callId, callEndedPayload(callId, caller, callee, "AUDIO", "MISSED"));
        publish(TOPIC, callId, json);
        publish(TOPIC, callId, json);
        flush(TOPIC, callId);

        assertThat(notificationsOf(callee)).hasSize(1);
    }

    @Test
    void anOnlineCalleeIsNotifiedButGetsNoPush() throws Exception {
        registerDevice(callee, newToken());
        PEERS.setOnline(callee);

        awaitHandled(callEnded(caller, callee, "AUDIO", "MISSED"));

        assertThat(notificationsOf(callee)).hasSize(1);
        assertThat(deliveriesOf(callee)).isEmpty();
    }

    @Test
    void anOfflineCalleeWithADeviceGetsAPushQueued() throws Exception {
        registerDevice(callee, newToken());

        awaitHandled(callEnded(caller, callee, "VIDEO", "MISSED"));

        assertThat(notificationsOf(callee)).hasSize(1);
        List<Map<String, Object>> deliveries = deliveriesOf(callee);
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.get(0)).containsEntry("status", "PENDING")
                .containsEntry("notification_id", notificationsOf(callee).get(0).get("id"));
    }

    @Test
    void anUnknownCallerIsCalledSomeone() {
        UUID stranger = newId();          // user-service does not know this id
        awaitHandled(callEnded(stranger, callee, "AUDIO", "MISSED"));
        assertThat(notificationsOf(callee).get(0)).containsEntry("title", "Someone");
    }

    @Test
    void userServiceBeingDownDoesNotStopTheNotification() {
        PEERS.userDown = true;
        UUID otherCaller = newId();      // not cached by the name lookup yet
        awaitHandled(callEnded(otherCaller, callee, "AUDIO", "MISSED"));
        assertThat(notificationsOf(callee).get(0)).containsEntry("title", "Someone");
    }

    @Test
    void theMissedCallIsAlsoPublishedLive() {
        try (LiveListener live = new LiveListener()) {
            awaitHandled(callEnded(caller, callee, "VIDEO", "MISSED"));
            eventually(() -> !live.forUser(callee).isEmpty(), "live event");
            String envelope = live.forUser(callee).get(0);
            assertThat(envelope).contains("/queue/notifications").contains("NOTIFICATION")
                    .contains("MISSED_CALL").contains("Missed video call");
        }
    }

    @Test
    void theMissedCallAppearsInTheApiAndCountsAsUnread() throws Exception {
        awaitHandled(callEnded(caller, callee, "AUDIO", "MISSED"));

        var list = getAs(callee, "/api/notifications");
        assertStatus(list, 200);
        assertThat(field(list, "$.data.items[0].type")).isEqualTo("MISSED_CALL");
        assertThat(field(list, "$.data.items[0].body")).isEqualTo("Missed voice call");
        assertThat(field(list, "$.data.items[0].conversationId")).isNull();
        assertThat(field(getAs(callee, "/api/notifications/unread-count"), "$.data.unreadCount")).isEqualTo("1");
    }

    @Test
    void aBrokenCallEventGoesToItsOwnDeadLetterTopic() {
        UUID callId = newId();
        UUID eventId = newId();
        // a CallEndedEvent whose payload has no calleeId
        publishRaw(TOPIC, callId.toString(), "{\"eventId\":\"" + eventId + "\",\"eventType\":\"CallEndedEvent\","
                + "\"payload\":{\"callId\":\"" + callId + "\",\"callerId\":\"" + caller + "\",\"type\":\"AUDIO\","
                + "\"outcome\":\"MISSED\"}}");

        assertThat(findInTopic("call.events.dlt", eventId.toString(), Duration.ofSeconds(30))).isNotNull();
        assertThat(eventHandled(eventId)).isFalse();
    }

    @Test
    void textThatIsNotJsonOnCallEventsGoesToTheDeadLetterTopic() {
        String marker = "garbage-" + UUID.randomUUID();
        publishRaw(TOPIC, UUID.randomUUID().toString(), "not json {{ " + marker);
        assertThat(findInTopic("call.events.dlt", marker, Duration.ofSeconds(30))).isNotNull();
    }

    @Test
    void otherCallEventTypesAreIgnored() {
        UUID callId = newId();
        UUID eventId = newId();
        publish(TOPIC, callId, event(eventId, "CallInitiatedEvent", callId, Map.of(
                "callId", callId.toString(), "callerId", caller.toString(), "calleeId", callee.toString())));
        flush(TOPIC, callId);
        assertThat(notificationCount(callee)).isZero();
        assertThat(findInTopic("call.events.dlt", eventId.toString(), Duration.ofSeconds(3))).isNull();
    }
}
