package com.chatapp.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.chatapp.conversation.client.UserServiceClient;
import com.chatapp.conversation.dto.request.CreateConversationRequest;
import com.chatapp.conversation.dto.response.ConversationResponse;
import com.chatapp.conversation.dto.response.InternalConversationResponse;
import com.chatapp.conversation.dto.response.PublicProfileResponse;
import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.entity.ConversationParticipant;
import com.chatapp.conversation.entity.GroupSettings;
import com.chatapp.conversation.entity.MembershipPeriod;
import com.chatapp.conversation.entity.PrivateConversationKey;
import com.chatapp.conversation.enums.ConversationType;
import com.chatapp.conversation.enums.ParticipantRole;
import com.chatapp.conversation.exception.ApiException;
import com.chatapp.conversation.exception.DependencyUnavailableException;
import com.chatapp.conversation.exception.ResourceNotFoundException;
import com.chatapp.conversation.kafka.producer.ConversationCreatedPayload;
import com.chatapp.conversation.kafka.producer.OutboxService;
import com.chatapp.conversation.mapper.ConversationMapperImpl;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.conversation.repository.GroupSettingsRepository;
import com.chatapp.conversation.repository.MembershipPeriodRepository;
import com.chatapp.conversation.repository.ParticipantRepository;
import com.chatapp.conversation.repository.PrivateKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Plain unit test: every collaborator is a Mockito mock, no Spring, no database.
 * (How a conversation is SHOWN is tested in ConversationViewServiceTest, groups in GroupServiceTest.)
 */
class ConversationServiceTest {

    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final ParticipantRepository participantRepository = mock(ParticipantRepository.class);
    private final PrivateKeyRepository privateKeyRepository = mock(PrivateKeyRepository.class);
    private final MembershipPeriodRepository periodRepository = mock(MembershipPeriodRepository.class);
    private final GroupSettingsRepository groupSettingsRepository = mock(GroupSettingsRepository.class);
    private final UserServiceClient userServiceClient = mock(UserServiceClient.class);
    private final OutboxService outboxService = mock(OutboxService.class);
    private final ConversationViewService viewService = mock(ConversationViewService.class);
    private final GroupService groupService = mock(GroupService.class);

    private ConversationService service;

    private final UUID me = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final ConversationResponse shown = mock(ConversationResponse.class);

    @BeforeEach
    void setUp() {
        service = new ConversationService(conversationRepository, participantRepository, privateKeyRepository,
                periodRepository, groupSettingsRepository, userServiceClient, outboxService,
                new ConversationMapperImpl(), viewService, groupService, mock(PlatformTransactionManager.class));

        // The "database" gives every saved conversation an id, like Hibernate does.
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> {
            Conversation c = invocation.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });
        when(participantRepository.save(any(ConversationParticipant.class))).thenAnswer(i -> i.getArgument(0));
        when(viewService.viewOne(eq(me), any(), anyMap())).thenReturn(shown);
    }

    private static PublicProfileResponse profile(UUID id, String name) {
        return new PublicProfileResponse(id, name.toLowerCase(), name, null, null);
    }

    private void bothUsersExistAndAreNotBlocked() {
        when(userServiceClient.findSummaries(eq(me), anyList()))
                .thenReturn(List.of(profile(me, "Me"), profile(other, "Other")));
        when(userServiceClient.isBlockedEitherWay(me, other)).thenReturn(false);
    }

    private static CreateConversationRequest privateWith(UUID userId) {
        return new CreateConversationRequest(ConversationType.PRIVATE, userId);
    }

    // ------------------------------------------------------------ create: rules

    @Test
    void groupRequestsAreHandledByTheGroupService() {
        CreateConversationRequest request = CreateConversationRequest.group("Trip", null, List.of(other));
        when(groupService.createGroup(me, request)).thenReturn(shown);

        ConversationService.CreateResult result = service.create(me, request);

        assertThat(result.created()).isTrue();
        assertThat(result.conversation()).isSameAs(shown);
        verifyNoInteractions(userServiceClient, privateKeyRepository);
    }

    @Test
    void privateConversationNeedsTheOtherUser() {
        assertThatThrownBy(() -> service.create(me, privateWith(null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void cannotStartAConversationWithYourself() {
        assertThatThrownBy(() -> service.create(me, privateWith(me)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(userServiceClient);
    }

    @Test
    void unknownUserIs404() {
        // user-service only knows the caller
        when(userServiceClient.findSummaries(eq(me), anyList())).thenReturn(List.of(profile(me, "Me")));

        assertThatThrownBy(() -> service.create(me, privateWith(other)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void blockedUsersGet403WithMessagingNotAllowed() {
        when(userServiceClient.findSummaries(eq(me), anyList()))
                .thenReturn(List.of(profile(me, "Me"), profile(other, "Other")));
        when(userServiceClient.isBlockedEitherWay(me, other)).thenReturn(true);

        assertThatThrownBy(() -> service.create(me, privateWith(other)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getErrorCode()).isEqualTo("MESSAGING_NOT_ALLOWED");
                });
        verify(conversationRepository, never()).save(any());
        verify(outboxService, never()).queue(any(), any(), any());
    }

    @Test
    void userServiceDownMeansNothingIsSaved() {
        when(userServiceClient.findSummaries(eq(me), anyList()))
                .thenThrow(new DependencyUnavailableException("down"));

        assertThatThrownBy(() -> service.create(me, privateWith(other)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getErrorCode()).isEqualTo("DEPENDENCY_UNAVAILABLE");
                });
        verify(conversationRepository, never()).save(any());
    }

    // ----------------------------------------------------- create: new / existing

    @Test
    void newPairCreatesConversationParticipantsPeriodsKeyAndEventAllWithTheSameTime() {
        bothUsersExistAndAreNotBlocked();
        when(privateKeyRepository.findByUserLowIdAndUserHighId(any(), any())).thenReturn(Optional.empty());

        ConversationService.CreateResult result = service.create(me, privateWith(other));

        assertThat(result.created()).isTrue();
        assertThat(result.conversation()).isSameAs(shown);

        ArgumentCaptor<ConversationParticipant> participants = ArgumentCaptor.forClass(ConversationParticipant.class);
        verify(participantRepository, times(2)).save(participants.capture());
        ArgumentCaptor<MembershipPeriod> periods = ArgumentCaptor.forClass(MembershipPeriod.class);
        verify(periodRepository, times(2)).save(periods.capture());
        ArgumentCaptor<PrivateConversationKey> key = ArgumentCaptor.forClass(PrivateConversationKey.class);
        verify(privateKeyRepository).saveAndFlush(key.capture());
        assertThat(key.getValue().getUserLowId()).isEqualTo(PrivateConversationKey.lowerOf(me, other));
        assertThat(key.getValue().getUserHighId()).isEqualTo(PrivateConversationKey.higherOf(me, other));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).queue(eq("ConversationCreatedEvent"), any(), payload.capture());
        ConversationCreatedPayload event = (ConversationCreatedPayload) payload.getValue();
        assertThat(event.type()).isEqualTo(ConversationType.PRIVATE);
        assertThat(event.participantIds()).containsExactly(me, other);
        assertThat(event.createdBy()).isEqualTo(me);
        assertThat(event.groupName()).isNull();
        assertThat(event.createdByName()).isNull();

        // one time for everything, cut to microseconds (the database precision)
        Instant createdAt = event.createdAt();
        assertThat(createdAt).isEqualTo(createdAt.truncatedTo(ChronoUnit.MICROS));
        assertThat(participants.getAllValues()).allSatisfy(p -> {
            assertThat(p.getJoinedAt()).isEqualTo(createdAt);
            assertThat(p.getRole()).isEqualTo(ParticipantRole.MEMBER);
        });
        assertThat(periods.getAllValues()).allSatisfy(p -> {
            assertThat(p.getJoinedAt()).isEqualTo(createdAt);
            assertThat(p.getLeftAt()).isNull();
        });
    }

    @Test
    void existingPairReturnsTheSameConversationWithoutSavingAnything() {
        bothUsersExistAndAreNotBlocked();
        Conversation existing = existingConversation();

        ConversationService.CreateResult result = service.create(me, privateWith(other));

        assertThat(result.created()).isFalse();
        verify(viewService).viewOne(eq(me), eq(existing.getId()), anyMap());
        verify(conversationRepository, never()).save(any());
        verify(privateKeyRepository, never()).saveAndFlush(any());
        verify(outboxService, never()).queue(any(), any(), any());
    }

    @Test
    void raceLostToAnotherRequestReturnsTheWinnersConversation() {
        bothUsersExistAndAreNotBlocked();
        Conversation winner = new Conversation(ConversationType.PRIVATE, other);
        winner.setId(UUID.randomUUID());
        // First look: nothing there. We try to create, the unique key says "someone was faster". Second look: found.
        when(privateKeyRepository.findByUserLowIdAndUserHighId(any(), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new PrivateConversationKey(winner.getId(), me, other)));
        when(privateKeyRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate pair"));
        when(conversationRepository.findById(winner.getId())).thenReturn(Optional.of(winner));

        ConversationService.CreateResult result = service.create(me, privateWith(other));

        assertThat(result.created()).isFalse();
        verify(viewService).viewOne(eq(me), eq(winner.getId()), anyMap());
    }

    @Test
    void duplicateErrorWithoutAnExistingConversationIsNotHidden() {
        bothUsersExistAndAreNotBlocked();
        when(privateKeyRepository.findByUserLowIdAndUserHighId(any(), any())).thenReturn(Optional.empty());
        when(privateKeyRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("something else"));

        assertThatThrownBy(() -> service.create(me, privateWith(other)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void pairKeyIsTheSameWhicheverUserComesFirst() {
        UUID conversationId = UUID.randomUUID();
        PrivateConversationKey ab = new PrivateConversationKey(conversationId, me, other);
        PrivateConversationKey ba = new PrivateConversationKey(conversationId, other, me);

        assertThat(ab.getUserLowId()).isEqualTo(ba.getUserLowId());
        assertThat(ab.getUserHighId()).isEqualTo(ba.getUserHighId());
        assertThat(ab.getUserLowId()).isNotEqualTo(ab.getUserHighId());
    }

    // --------------------------------------------------------------------- read

    @Test
    void listShowsEveryRowOfMineIncludingGroupsILeft() {
        List<ConversationParticipant> myRows = List.of(
                new ConversationParticipant(UUID.randomUUID(), me, ParticipantRole.MEMBER));
        when(participantRepository.findByUserId(me)).thenReturn(myRows);
        when(viewService.view(eq(me), eq(myRows), anyMap())).thenReturn(List.of(shown));

        assertThat(service.list(me)).containsExactly(shown);
    }

    // ----------------------------------------------------------------- internal

    @Test
    void internalAnswerHasAdminsSettingAndEveryPeriod() {
        Conversation group = new Conversation(ConversationType.GROUP, me);
        group.setId(UUID.randomUUID());
        Instant t1 = Instant.parse("2026-11-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-11-02T10:00:00Z");
        ConversationParticipant admin = new ConversationParticipant(group.getId(), me, ParticipantRole.ADMIN, t1);
        ConversationParticipant member = new ConversationParticipant(group.getId(), other, ParticipantRole.MEMBER, t2);
        GroupSettings settings = new GroupSettings(group.getId(), "Trip", null, t1);
        settings.setOnlyAdminsCanSend(true);
        MembershipPeriod first = period(group.getId(), other, t1, t1.plusSeconds(60));
        MembershipPeriod second = period(group.getId(), other, t2, null);
        MembershipPeriod mine = period(group.getId(), me, t1, null);

        when(conversationRepository.findById(group.getId())).thenReturn(Optional.of(group));
        when(participantRepository.findByConversationIdAndActiveTrue(group.getId())).thenReturn(List.of(member, admin));
        when(groupSettingsRepository.findById(group.getId())).thenReturn(Optional.of(settings));
        when(periodRepository.findByConversationIdOrderByJoinedAtAscUserIdAsc(group.getId()))
                .thenReturn(List.of(first, mine, second));

        InternalConversationResponse answer = service.getForInternalUse(group.getId());

        assertThat(answer.type()).isEqualTo(ConversationType.GROUP);
        assertThat(answer.participantIds()).containsExactly(me, other);   // in join order
        assertThat(answer.adminIds()).containsExactly(me);
        assertThat(answer.onlyAdminsCanSend()).isTrue();
        assertThat(answer.members()).hasSize(3);
        assertThat(answer.members().get(0).userId()).isEqualTo(other);
        assertThat(answer.members().get(0).leftAt()).isEqualTo(t1.plusSeconds(60));
    }

    @Test
    void internalAnswerForAPrivateChatHasNoAdminsAndNeverAsksForGroupSettings() {
        Conversation chat = new Conversation(ConversationType.PRIVATE, me);
        chat.setId(UUID.randomUUID());
        when(conversationRepository.findById(chat.getId())).thenReturn(Optional.of(chat));
        when(participantRepository.findByConversationIdAndActiveTrue(chat.getId())).thenReturn(List.of(
                new ConversationParticipant(chat.getId(), me, ParticipantRole.MEMBER),
                new ConversationParticipant(chat.getId(), other, ParticipantRole.MEMBER)));

        InternalConversationResponse answer = service.getForInternalUse(chat.getId());

        assertThat(answer.adminIds()).isEmpty();
        assertThat(answer.onlyAdminsCanSend()).isFalse();
        verifyNoInteractions(groupSettingsRepository);
    }

    @Test
    void internalAnswerForAnUnknownIdIs404() {
        UUID unknown = UUID.randomUUID();
        when(conversationRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getForInternalUse(unknown)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ helpers

    private Conversation existingConversation() {
        Conversation existing = new Conversation(ConversationType.PRIVATE, other);
        existing.setId(UUID.randomUUID());
        when(privateKeyRepository.findByUserLowIdAndUserHighId(any(), any()))
                .thenReturn(Optional.of(new PrivateConversationKey(existing.getId(), me, other)));
        when(conversationRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        return existing;
    }

    private static MembershipPeriod period(UUID conversationId, UUID userId, Instant joinedAt, Instant leftAt) {
        MembershipPeriod period = new MembershipPeriod(conversationId, userId, joinedAt);
        period.setLeftAt(leftAt);
        return period;
    }
}
