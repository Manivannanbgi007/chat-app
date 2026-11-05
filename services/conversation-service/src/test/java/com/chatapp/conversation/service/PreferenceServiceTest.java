package com.chatapp.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.chatapp.conversation.dto.response.ConversationResponse;
import com.chatapp.conversation.entity.ConversationParticipant;
import com.chatapp.conversation.enums.MuteDuration;
import com.chatapp.conversation.enums.ParticipantRole;
import com.chatapp.conversation.exception.ApiException;
import com.chatapp.conversation.exception.ResourceNotFoundException;
import com.chatapp.conversation.repository.ParticipantRepository;
import com.chatapp.conversation.util.Times;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;

/** Mute, archive and pin on my own participant rows (kept in a list instead of a database). */
class PreferenceServiceTest {

    private final ParticipantRepository participantRepository = mock(ParticipantRepository.class);
    private final ConversationViewService viewService = mock(ConversationViewService.class);
    private final PreferenceService service =
            new PreferenceService(participantRepository, viewService, mock(PlatformTransactionManager.class));

    private final UUID me = UUID.randomUUID();
    private final List<ConversationParticipant> myRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(participantRepository.findByConversationIdAndUserId(any(), any())).thenAnswer(i -> myRows.stream()
                .filter(r -> r.getConversationId().equals(i.getArgument(0)) && r.getUserId().equals(i.getArgument(1)))
                .findFirst());
        when(participantRepository.findByUserIdForUpdate(me)).thenReturn(myRows);
        when(participantRepository.save(any(ConversationParticipant.class))).thenAnswer(i -> i.getArgument(0));
        when(viewService.viewOne(any(), any(), anyMap())).thenReturn(mock(ConversationResponse.class));
    }

    @Test
    void muteDurations() {
        ConversationParticipant chat = chat();
        Instant before = Times.now();

        service.mute(me, chat.getConversationId(), MuteDuration.EIGHT_HOURS);
        assertThat(chat.getMutedUntil()).isBetween(before.plus(Duration.ofHours(8)), Times.now().plus(Duration.ofHours(8)));

        service.mute(me, chat.getConversationId(), MuteDuration.ONE_WEEK);
        assertThat(chat.getMutedUntil()).isBetween(before.plus(Duration.ofDays(7)), Times.now().plus(Duration.ofDays(7)));

        service.mute(me, chat.getConversationId(), MuteDuration.ALWAYS);
        assertThat(chat.getMutedUntil()).isEqualTo(Instant.parse("9999-12-31T23:59:59Z"));

        service.unmute(me, chat.getConversationId());
        assertThat(chat.getMutedUntil()).isNull();
    }

    @Test
    void archivingUnpinsAndPinningUnarchives() {
        ConversationParticipant chat = chat();

        service.pin(me, chat.getConversationId());
        assertThat(chat.getPinnedAt()).isNotNull();

        service.archive(me, chat.getConversationId());
        assertThat(chat.getArchivedAt()).isNotNull();
        assertThat(chat.getPinnedAt()).isNull();

        service.pin(me, chat.getConversationId());
        assertThat(chat.getPinnedAt()).isNotNull();
        assertThat(chat.getArchivedAt()).isNull();

        service.unpin(me, chat.getConversationId());
        assertThat(chat.getPinnedAt()).isNull();
    }

    @Test
    void archivingTwiceKeepsTheFirstTime() {
        ConversationParticipant chat = chat();
        service.archive(me, chat.getConversationId());
        Instant first = chat.getArchivedAt();

        service.archive(me, chat.getConversationId());

        assertThat(chat.getArchivedAt()).isEqualTo(first);
        service.unarchive(me, chat.getConversationId());
        assertThat(chat.getArchivedAt()).isNull();
    }

    @Test
    void atMostThreeChatsCanBePinned() {
        List<ConversationParticipant> pinned = List.of(chat(), chat(), chat());
        pinned.forEach(row -> service.pin(me, row.getConversationId()));
        ConversationParticipant fourth = chat();

        assertThatThrownBy(() -> service.pin(me, fourth.getConversationId()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo("PIN_LIMIT_REACHED");
                });
        assertThat(fourth.getPinnedAt()).isNull();

        // pinning one that is already pinned is fine (nothing changes)
        service.pin(me, pinned.get(0).getConversationId());
        // after unpinning one there is room again
        service.unpin(me, pinned.get(1).getConversationId());
        service.pin(me, fourth.getConversationId());
        assertThat(fourth.getPinnedAt()).isNotNull();
    }

    @Test
    void aChatIWasNeverInIs404() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> service.mute(me, unknown, MuteDuration.ALWAYS)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.pin(me, unknown)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aGroupILeftCanStillBeArchived() {
        ConversationParticipant left = chat();
        left.leave(Times.now());

        service.archive(me, left.getConversationId());

        assertThat(left.getArchivedAt()).isNotNull();
    }

    private ConversationParticipant chat() {
        ConversationParticipant row = new ConversationParticipant(UUID.randomUUID(), me, ParticipantRole.MEMBER);
        myRows.add(row);
        return row;
    }
}
