package com.chatapp.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.chatapp.conversation.client.UserServiceClient;
import com.chatapp.conversation.dto.response.ConversationResponse;
import com.chatapp.conversation.dto.response.PublicProfileResponse;
import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.entity.ConversationParticipant;
import com.chatapp.conversation.entity.GroupSettings;
import com.chatapp.conversation.enums.ConversationType;
import com.chatapp.conversation.enums.ParticipantRole;
import com.chatapp.conversation.exception.ResourceNotFoundException;
import com.chatapp.conversation.mapper.ConversationMapperImpl;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.conversation.repository.GroupSettingsRepository;
import com.chatapp.conversation.repository.ParticipantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What one user sees of their conversations: private chats, groups, and groups they left. */
class ConversationViewServiceTest {

    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final ParticipantRepository participantRepository = mock(ParticipantRepository.class);
    private final GroupSettingsRepository groupSettingsRepository = mock(GroupSettingsRepository.class);
    private final UserServiceClient userServiceClient = mock(UserServiceClient.class);

    private ConversationViewService service;

    private final UUID me = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final List<Conversation> conversations = new ArrayList<>();
    private final List<ConversationParticipant> activeRows = new ArrayList<>();
    private final List<GroupSettings> groups = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ConversationViewService(conversationRepository, participantRepository, groupSettingsRepository,
                userServiceClient, new ConversationMapperImpl());
        when(conversationRepository.findByIdIn(any())).thenReturn(conversations);
        when(participantRepository.findByConversationIdInAndActiveTrue(any())).thenReturn(activeRows);
        when(groupSettingsRepository.findAllById(any())).thenReturn(groups);
    }

    @Test
    void noRowsMeansNoQueriesAndNoUserServiceCall() {
        assertThat(service.view(me, List.of(), Map.of())).isEmpty();
        verifyNoInteractions(userServiceClient, conversationRepository);
    }

    @Test
    void viewOneOfAConversationIWasNeverInIs404AndNeverAsksUserService() {
        UUID conversationId = UUID.randomUUID();
        when(participantRepository.findByConversationIdAndUserId(conversationId, me)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.viewOne(me, conversationId, Map.of()))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(userServiceClient);
    }

    @Test
    void listIsSortedByLastActivityAndAsksUserServiceOnlyOnce() {
        UUID thirdUser = UUID.randomUUID();
        Conversation old = privateChat(Instant.parse("2026-01-01T10:00:00Z"), null, other);
        Conversation recentMessage = privateChat(Instant.parse("2026-01-01T09:00:00Z"),
                Instant.parse("2026-03-01T10:00:00Z"), thirdUser);
        Conversation noMessageYet = privateChat(Instant.parse("2026-02-01T10:00:00Z"), null, other);
        // user-service knows "other" but has no idea who thirdUser is
        when(userServiceClient.findSummaries(eq(me), anyList()))
                .thenReturn(List.of(profile(me, "Me"), profile(other, "Other")));

        List<ConversationResponse> list = service.view(me, myRows(old, recentMessage, noMessageYet), Map.of());

        assertThat(list).extracting(ConversationResponse::id)
                .containsExactly(recentMessage.getId(), noMessageYet.getId(), old.getId());
        assertThat(list.get(0).otherUser().displayName()).isEqualTo("Unknown user");   // placeholder, never omitted
        assertThat(list.get(1).otherUser().id()).isEqualTo(other);
        assertThat(list.get(1).group()).isNull();
        assertThat(list.get(1).membership().active()).isTrue();
        verify(userServiceClient, times(1)).findSummaries(eq(me), anyList());
    }

    @Test
    void knownProfilesAreNotAskedForAgain() {
        Conversation chat = privateChat(Instant.parse("2026-01-01T10:00:00Z"), null, other);

        ConversationResponse view = service.view(me, myRows(chat),
                Map.of(me, profile(me, "Me"), other, profile(other, "Other"))).get(0);

        assertThat(view.otherUser().displayName()).isEqualTo("Other");
        verify(userServiceClient, never()).findSummaries(any(), anyList());
    }

    @Test
    void aGroupShowsMembersInJoinOrderAdminsAndSettings() {
        UUID carol = UUID.randomUUID();
        Conversation group = groupChat("Trip");
        addActive(group, carol, ParticipantRole.MEMBER, Instant.parse("2026-01-03T10:00:00Z"));
        addActive(group, me, ParticipantRole.ADMIN, Instant.parse("2026-01-01T10:00:00Z"));
        addActive(group, other, ParticipantRole.ADMIN, Instant.parse("2026-01-02T10:00:00Z"));
        when(userServiceClient.findSummaries(eq(me), anyList()))
                .thenReturn(List.of(profile(me, "Me"), profile(other, "Other"), profile(carol, "Carol")));
        ConversationParticipant myRow = activeRows.stream().filter(p -> p.getUserId().equals(me)).findFirst().orElseThrow();

        ConversationResponse view = service.view(me, List.of(myRow), Map.of()).get(0);

        assertThat(view.type()).isEqualTo(ConversationType.GROUP);
        assertThat(view.otherUser()).isNull();
        assertThat(view.participants()).extracting(PublicProfileResponse::id).containsExactly(me, other, carol);
        assertThat(view.group().name()).isEqualTo("Trip");
        assertThat(view.group().createdBy()).isEqualTo(me);
        assertThat(view.group().adminIds()).containsExactly(me, other);
        assertThat(view.group().onlyAdminsCanSend()).isFalse();
        assertThat(view.membership().role()).isEqualTo(ParticipantRole.ADMIN);
        assertThat(view.preferences().archived()).isFalse();
    }

    @Test
    void aGroupILeftHidesMembersAndAdminsAndNeedsNoProfiles() {
        Conversation group = groupChat("Trip");
        addActive(group, other, ParticipantRole.ADMIN, Instant.parse("2026-01-01T10:00:00Z"));
        ConversationParticipant myRow = new ConversationParticipant(group.getId(), me, ParticipantRole.MEMBER,
                Instant.parse("2026-01-01T10:00:00Z"));
        myRow.leave(Instant.parse("2026-01-05T10:00:00Z"));
        myRow.setArchivedAt(Instant.parse("2026-01-06T10:00:00Z"));

        ConversationResponse view = service.view(me, List.of(myRow), Map.of()).get(0);

        assertThat(view.participants()).isEmpty();
        assertThat(view.group().adminIds()).isEmpty();
        assertThat(view.group().name()).isEqualTo("Trip");
        assertThat(view.membership().active()).isFalse();
        assertThat(view.membership().leftAt()).isEqualTo(Instant.parse("2026-01-05T10:00:00Z"));
        assertThat(view.preferences().archived()).isTrue();
        verifyNoInteractions(userServiceClient);
    }

    // ------------------------------------------------------------------ helpers

    private Conversation privateChat(Instant createdAt, Instant lastMessageAt, UUID otherUser) {
        Conversation c = new Conversation(ConversationType.PRIVATE, me, createdAt);
        c.setId(UUID.randomUUID());
        c.setLastMessageAt(lastMessageAt);
        conversations.add(c);
        addActive(c, me, ParticipantRole.MEMBER, createdAt);
        addActive(c, otherUser, ParticipantRole.MEMBER, createdAt);
        return c;
    }

    private Conversation groupChat(String name) {
        Conversation c = new Conversation(ConversationType.GROUP, me, Instant.parse("2026-01-01T10:00:00Z"));
        c.setId(UUID.randomUUID());
        conversations.add(c);
        groups.add(new GroupSettings(c.getId(), name, null, c.getCreatedAt()));
        return c;
    }

    private void addActive(Conversation conversation, UUID userId, ParticipantRole role, Instant joinedAt) {
        activeRows.add(new ConversationParticipant(conversation.getId(), userId, role, joinedAt));
    }

    private List<ConversationParticipant> myRows(Conversation... chats) {
        List<ConversationParticipant> rows = new ArrayList<>();
        for (Conversation chat : chats) {
            rows.add(activeRows.stream()
                    .filter(p -> p.getConversationId().equals(chat.getId()) && p.getUserId().equals(me))
                    .findFirst().orElseThrow());
        }
        return rows;
    }

    private static PublicProfileResponse profile(UUID id, String name) {
        return new PublicProfileResponse(id, name.toLowerCase(), name, null, null);
    }
}
