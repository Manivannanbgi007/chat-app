package com.chatapp.message.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.chatapp.message.entity.ConversationMember;
import com.chatapp.message.entity.Message;
import com.chatapp.message.entity.MessageReceipt;
import com.chatapp.message.enums.ReceiptStatus;
import org.junit.jupiter.api.Test;

/** The group status rule on its own (no Spring, no database). */
class GroupStatusServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");
    private final UUID conversation = UUID.randomUUID();
    private final UUID sender = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();

    private ConversationMember period(UUID user, Instant joined, Instant left) {
        ConversationMember member = new ConversationMember();
        member.setConversationId(conversation);
        member.setUserId(user);
        member.setConversationType("GROUP");
        member.setJoinedAt(joined);
        member.setLeftAt(left);
        return member;
    }

    private Message messageAt(Instant time) {
        Message message = new Message();
        message.setId(UUID.randomUUID());
        message.setConversationId(conversation);
        message.setSenderId(sender);
        message.setCreatedAt(time);
        return message;
    }

    private MessageReceipt receipt(Message message, UUID user, ReceiptStatus status) {
        MessageReceipt receipt = new MessageReceipt();
        receipt.setMessageId(message.getId());
        receipt.setRecipientId(user);
        receipt.setDeviceId(UUID.randomUUID());
        receipt.setStatus(status);
        receipt.setDeliveredAt(T0);
        return receipt;
    }

    private List<ConversationMember> everybodySinceT0() {
        return List.of(period(sender, T0, null), period(bob, T0, null), period(carol, T0, null));
    }

    @Test
    void sentUntilEveryRecipientHasIt() {
        Message message = messageAt(T0.plusSeconds(10));
        List<ConversationMember> periods = everybodySinceT0();

        assertThat(GroupStatusService.combinedStatus(message, periods, List.of())).isEqualTo("SENT");
        assertThat(GroupStatusService.combinedStatus(message, periods,
                List.of(receipt(message, bob, ReceiptStatus.DELIVERED)))).isEqualTo("SENT");
        assertThat(GroupStatusService.combinedStatus(message, periods, List.of(
                receipt(message, bob, ReceiptStatus.READ), receipt(message, carol, ReceiptStatus.DELIVERED))))
                .isEqualTo("DELIVERED");
        assertThat(GroupStatusService.combinedStatus(message, periods, List.of(
                receipt(message, bob, ReceiptStatus.READ), receipt(message, carol, ReceiptStatus.READ))))
                .isEqualTo("READ");
    }

    @Test
    void membersWhoLeftOrJoinedLaterAreNotRecipients() {
        Message message = messageAt(T0.plusSeconds(10));
        UUID dave = UUID.randomUUID();
        List<ConversationMember> periods = List.of(
                period(sender, T0, null),
                period(bob, T0, null),
                period(carol, T0, T0.plusSeconds(20)),          // left after the message: no longer holds it back
                period(dave, T0.plusSeconds(30), null));        // joined after the message

        assertThat(GroupStatusService.recipientsOf(message, periods)).containsExactly(bob);
        assertThat(GroupStatusService.combinedStatus(message, periods,
                List.of(receipt(message, bob, ReceiptStatus.READ)))).isEqualTo("READ");
    }

    @Test
    void aMemberWhoCameBackIsARecipientOnlyOfMessagesFromTheirPeriods() {
        List<ConversationMember> periods = List.of(
                period(sender, T0, null),
                period(bob, T0, T0.plusSeconds(10)),
                period(bob, T0.plusSeconds(20), null));

        assertThat(GroupStatusService.recipientsOf(messageAt(T0.plusSeconds(5)), periods)).containsExactly(bob);
        assertThat(GroupStatusService.recipientsOf(messageAt(T0.plusSeconds(15)), periods)).isEmpty();
        assertThat(GroupStatusService.recipientsOf(messageAt(T0.plusSeconds(25)), periods)).containsExactly(bob);
    }

    @Test
    void periodsIncludeBothEnds() {
        ConversationMember closed = period(bob, T0, T0.plusSeconds(10));
        assertThat(closed.covers(T0)).isTrue();
        assertThat(closed.covers(T0.plusSeconds(10))).isTrue();
        assertThat(closed.covers(T0.minusNanos(1000))).isFalse();
        assertThat(closed.covers(T0.plusSeconds(10).plusNanos(1000))).isFalse();
    }

    @Test
    void noRecipientsMeansSent() {
        Message message = messageAt(T0.plusSeconds(10));
        assertThat(GroupStatusService.combinedStatus(message, List.of(period(sender, T0, null)), List.of())).isEqualTo("SENT");
    }
}
