package com.chatapp.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.chatapp.conversation.client.UserServiceClient;
import com.chatapp.conversation.dto.request.AddMembersRequest;
import com.chatapp.conversation.dto.request.CreateConversationRequest;
import com.chatapp.conversation.dto.request.UpdateGroupRequest;
import com.chatapp.conversation.dto.response.ConversationResponse;
import com.chatapp.conversation.dto.response.PublicProfileResponse;
import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.entity.ConversationParticipant;
import com.chatapp.conversation.entity.GroupSettings;
import com.chatapp.conversation.entity.MembershipPeriod;
import com.chatapp.conversation.enums.ConversationType;
import com.chatapp.conversation.enums.ParticipantRole;
import com.chatapp.conversation.exception.ApiException;
import com.chatapp.conversation.exception.FieldValidationException;
import com.chatapp.conversation.exception.GroupAddNotAllowedException;
import com.chatapp.conversation.exception.ResourceNotFoundException;
import com.chatapp.conversation.kafka.producer.ConversationCreatedPayload;
import com.chatapp.conversation.kafka.producer.GroupEvents;
import com.chatapp.conversation.kafka.producer.OutboxService;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.conversation.repository.GroupSettingsRepository;
import com.chatapp.conversation.repository.MembershipPeriodRepository;
import com.chatapp.conversation.repository.ParticipantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The group rules, without Spring or a database: the repositories are mocks that read and write a small
 * in-memory group (the lists below), so each test can set up members and look at what changed.
 */
class GroupServiceTest {

    private static final int MAX_MEMBERS = 5;

    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final ParticipantRepository participantRepository = mock(ParticipantRepository.class);
    private final GroupSettingsRepository groupSettingsRepository = mock(GroupSettingsRepository.class);
    private final MembershipPeriodRepository periodRepository = mock(MembershipPeriodRepository.class);
    private final UserServiceClient userServiceClient = mock(UserServiceClient.class);
    private final OutboxService outboxService = mock(OutboxService.class);
    private final ConversationViewService viewService = mock(ConversationViewService.class);

    private GroupService service;

    private final UUID me = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();

    // the in-memory "database"
    private Conversation group;
    private GroupSettings settings;
    private final List<ConversationParticipant> rows = new ArrayList<>();
    private final List<MembershipPeriod> periods = new ArrayList<>();
    /** Every queued event: [eventType, payload]. */
    private final List<Object[]> events = new ArrayList<>();
    private final List<UUID> refusedByUserService = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new GroupService(conversationRepository, participantRepository, groupSettingsRepository,
                periodRepository, userServiceClient, outboxService, viewService,
                mock(PlatformTransactionManager.class), MAX_MEMBERS);

        group = new Conversation(ConversationType.GROUP, me, Instant.parse("2026-11-01T10:00:00Z"));
        group.setId(UUID.randomUUID());
        settings = new GroupSettings(group.getId(), "Trip", "Old description", group.getCreatedAt());

        when(conversationRepository.findById(any())).thenAnswer(i -> find(i.getArgument(0)));
        when(conversationRepository.findByIdForUpdate(any())).thenAnswer(i -> find(i.getArgument(0)));
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(i -> {
            Conversation saved = i.getArgument(0);
            saved.setId(group.getId());
            group = saved;
            return saved;
        });
        when(groupSettingsRepository.findById(any())).thenAnswer(i -> Optional.ofNullable(settings));
        when(groupSettingsRepository.save(any(GroupSettings.class))).thenAnswer(i -> settings = i.getArgument(0));
        when(participantRepository.findByConversationIdAndUserId(any(), any())).thenAnswer(i -> rows.stream()
                .filter(r -> r.getConversationId().equals(i.getArgument(0)) && r.getUserId().equals(i.getArgument(1)))
                .findFirst());
        when(participantRepository.findByConversationIdAndActiveTrue(any())).thenAnswer(i -> rows.stream()
                .filter(r -> r.getConversationId().equals(i.getArgument(0)) && r.isActive())
                .toList());
        when(participantRepository.save(any(ConversationParticipant.class))).thenAnswer(i -> {
            ConversationParticipant row = i.getArgument(0);
            if (!rows.contains(row)) {
                rows.add(row);
            }
            return row;
        });
        when(periodRepository.save(any(MembershipPeriod.class))).thenAnswer(i -> {
            MembershipPeriod period = i.getArgument(0);
            if (!periods.contains(period)) {
                periods.add(period);
            }
            return period;
        });
        when(periodRepository.findByConversationIdAndUserIdAndLeftAtIsNull(any(), any())).thenAnswer(i -> periods.stream()
                .filter(p -> p.getConversationId().equals(i.getArgument(0)) && p.getUserId().equals(i.getArgument(1))
                        && p.getLeftAt() == null)
                .toList());
        // user-service: everybody exists and is called "Name-xxxx"; refusals can be set per test
        when(userServiceClient.findSummaries(any(), anyList())).thenAnswer(i -> {
            List<UUID> ids = i.getArgument(1);
            return ids.stream().map(id -> new PublicProfileResponse(id, "u", name(id), null, null)).toList();
        });
        when(userServiceClient.groupAddNotAllowed(any(), anyList())).thenAnswer(i -> List.copyOf(refusedByUserService));
        doAnswer(i -> events.add(new Object[]{i.getArgument(0), i.getArgument(2)}))
                .when(outboxService).queue(any(), any(), any());
        when(viewService.viewOne(any(), any(), anyMap())).thenReturn(mock(ConversationResponse.class));
    }

    // ------------------------------------------------------------------ create

    @Test
    void createMakesMeAdminTheOthersMembersAndQueuesTheEvent() {
        service.createGroup(me, CreateConversationRequest.group("  Trip to Goa  ", "  Beach  ", List.of(bob, carol, bob, me)));

        assertThat(settings.getName()).isEqualTo("Trip to Goa");
        assertThat(settings.getDescription()).isEqualTo("Beach");
        assertThat(roleOf(me)).isEqualTo(ParticipantRole.ADMIN);
        assertThat(roleOf(bob)).isEqualTo(ParticipantRole.MEMBER);
        assertThat(rows).hasSize(3);                         // me once, bob once (duplicates ignored), carol
        assertThat(periods).hasSize(3).allSatisfy(p -> assertThat(p.getJoinedAt()).isEqualTo(group.getCreatedAt()));

        ConversationCreatedPayload event = onlyEvent("ConversationCreatedEvent", ConversationCreatedPayload.class);
        assertThat(event.type()).isEqualTo(ConversationType.GROUP);
        assertThat(event.participantIds()).containsExactly(me, bob, carol);
        assertThat(event.createdBy()).isEqualTo(me);
        assertThat(event.groupName()).isEqualTo("Trip to Goa");
        assertThat(event.createdByName()).isEqualTo(name(me));
        assertThat(event.createdAt()).isEqualTo(group.getCreatedAt());
    }

    @Test
    void createChecksNameDescriptionAndMembers() {
        assertField(() -> service.createGroup(me, CreateConversationRequest.group("   ", null, List.of(bob))), "name");
        assertField(() -> service.createGroup(me, CreateConversationRequest.group("x".repeat(101), null, List.of(bob))), "name");
        assertField(() -> service.createGroup(me, CreateConversationRequest.group("Ok", "d".repeat(501), List.of(bob))),
                "description");
        assertField(() -> service.createGroup(me, CreateConversationRequest.group("Ok", null, List.of(me))), "memberIds");
        assertField(() -> service.createGroup(me, CreateConversationRequest.group("Ok", null, null)), "memberIds");
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void createWithTooManyMembersIsGroupFull() {
        List<UUID> five = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        assertCode(() -> service.createGroup(me, CreateConversationRequest.group("Big", null, five)),
                HttpStatus.CONFLICT, "GROUP_FULL");
        verify(userServiceClient, never()).groupAddNotAllowed(any(), anyList());
    }

    @Test
    void createWithSomebodyWhoCannotBeAddedCreatesNothing() {
        refusedByUserService.add(carol);

        assertThatThrownBy(() -> service.createGroup(me, CreateConversationRequest.group("Trip", null, List.of(bob, carol))))
                .isInstanceOfSatisfying(GroupAddNotAllowedException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("GROUP_ADD_NOT_ALLOWED");
                    assertThat(e.getUserIds()).containsExactly(carol);
                });
        verify(conversationRepository, never()).save(any());
        assertThat(events).isEmpty();
    }

    // ----------------------------------------------------------------- members

    @Test
    void onlyAdminsAddMembers() {
        member(me, ParticipantRole.MEMBER);
        member(bob, ParticipantRole.ADMIN);

        assertCode(() -> service.addMembers(me, group.getId(), new AddMembersRequest(List.of(carol))),
                HttpStatus.FORBIDDEN, "NOT_GROUP_ADMIN");
    }

    @Test
    void addingOnlyCurrentMembersIsAlreadyMember() {
        member(me, ParticipantRole.ADMIN);
        member(bob, ParticipantRole.MEMBER);

        assertCode(() -> service.addMembers(me, group.getId(), new AddMembersRequest(List.of(bob, me))),
                HttpStatus.CONFLICT, "ALREADY_MEMBER");
    }

    @Test
    void addingBeyondTheLimitIsGroupFull() {
        member(me, ParticipantRole.ADMIN);
        member(bob, ParticipantRole.MEMBER);
        member(carol, ParticipantRole.MEMBER);

        assertCode(() -> service.addMembers(me, group.getId(),
                        new AddMembersRequest(List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))),
                HttpStatus.CONFLICT, "GROUP_FULL");
    }

    @Test
    void aFormerMemberIsAddedAgainOnTheSameRowWithANewPeriod() {
        member(me, ParticipantRole.ADMIN);
        ConversationParticipant bobRow = member(bob, ParticipantRole.ADMIN);
        bobRow.leave(Instant.parse("2026-11-02T10:00:00Z"));
        periods.get(periods.size() - 1).setLeftAt(Instant.parse("2026-11-02T10:00:00Z"));

        service.addMembers(me, group.getId(), new AddMembersRequest(List.of(bob, carol)));

        assertThat(rows.stream().filter(r -> r.getUserId().equals(bob))).hasSize(1);
        assertThat(bobRow.isActive()).isTrue();
        assertThat(bobRow.getRole()).isEqualTo(ParticipantRole.MEMBER);
        assertThat(bobRow.getLeftAt()).isNull();
        assertThat(periods.stream().filter(p -> p.getUserId().equals(bob))).hasSize(2);
        GroupEvents.MembersAdded event = onlyEvent(GroupEvents.MEMBERS_ADDED, GroupEvents.MembersAdded.class);
        assertThat(event.actorId()).isEqualTo(me);
        assertThat(event.actorName()).isEqualTo(name(me));
        assertThat(event.members()).extracting(GroupEvents.NamedUser::userId).containsExactly(bob, carol);
        assertThat(event.members().get(1).name()).isEqualTo(name(carol));
        assertThat(event.joinedAt()).isEqualTo(bobRow.getJoinedAt());
    }

    @Test
    void addingSomebodyWhoCannotBeAddedChangesNothing() {
        member(me, ParticipantRole.ADMIN);
        refusedByUserService.add(carol);

        assertCode(() -> service.addMembers(me, group.getId(), new AddMembersRequest(List.of(carol))),
                HttpStatus.FORBIDDEN, "GROUP_ADD_NOT_ALLOWED");
        assertThat(rows).hasSize(1);
        assertThat(events).isEmpty();
    }

    @Test
    void removeRules() {
        member(me, ParticipantRole.ADMIN);
        member(bob, ParticipantRole.MEMBER);

        assertCode(() -> service.removeMember(me, group.getId(), me), HttpStatus.BAD_REQUEST, "CANNOT_CHANGE_SELF");
        assertThatThrownBy(() -> service.removeMember(me, group.getId(), carol)).isInstanceOf(ResourceNotFoundException.class);
        assertCode(() -> service.removeMember(bob, group.getId(), me), HttpStatus.FORBIDDEN, "NOT_GROUP_ADMIN");
    }

    @Test
    void removingAMemberEndsTheirPeriodAndQueuesTheEvent() {
        member(me, ParticipantRole.ADMIN);
        ConversationParticipant bobRow = member(bob, ParticipantRole.ADMIN);

        service.removeMember(me, group.getId(), bob);

        assertThat(bobRow.isActive()).isFalse();
        assertThat(bobRow.getRole()).isEqualTo(ParticipantRole.MEMBER);
        MembershipPeriod bobPeriod = periods.stream().filter(p -> p.getUserId().equals(bob)).findFirst().orElseThrow();
        assertThat(bobPeriod.getLeftAt()).isEqualTo(bobRow.getLeftAt()).isNotNull();
        GroupEvents.MemberRemoved event = onlyEvent(GroupEvents.MEMBER_REMOVED, GroupEvents.MemberRemoved.class);
        assertThat(event.reason()).isEqualTo("REMOVED");
        assertThat(event.actorId()).isEqualTo(me);
        assertThat(event.userId()).isEqualTo(bob);
        assertThat(event.userName()).isEqualTo(name(bob));
        assertThat(event.leftAt()).isEqualTo(bobRow.getLeftAt());
    }

    @Test
    void changingARoleToTheSameValueQueuesNothing() {
        member(me, ParticipantRole.ADMIN);
        member(bob, ParticipantRole.MEMBER);

        service.changeRole(me, group.getId(), bob, ParticipantRole.MEMBER);
        assertThat(events).isEmpty();

        service.changeRole(me, group.getId(), bob, ParticipantRole.ADMIN);
        assertThat(roleOf(bob)).isEqualTo(ParticipantRole.ADMIN);
        GroupEvents.RoleChanged event = onlyEvent(GroupEvents.ROLE_CHANGED, GroupEvents.RoleChanged.class);
        assertThat(event.role()).isEqualTo("ADMIN");
        assertThat(event.actorId()).isEqualTo(me);
    }

    // ------------------------------------------------------------------- leave

    @Test
    void whenTheLastAdminLeavesTheEarliestJoinedMemberBecomesAdmin() {
        member(me, ParticipantRole.ADMIN, "2026-11-01T10:00:00Z");
        member(carol, ParticipantRole.MEMBER, "2026-11-03T10:00:00Z");
        member(bob, ParticipantRole.MEMBER, "2026-11-02T10:00:00Z");

        service.leave(me, group.getId());

        assertThat(roleOf(bob)).isEqualTo(ParticipantRole.ADMIN);
        assertThat(roleOf(carol)).isEqualTo(ParticipantRole.MEMBER);
        assertThat(events).extracting(e -> e[0]).containsExactly(GroupEvents.MEMBER_REMOVED, GroupEvents.ROLE_CHANGED);
        GroupEvents.MemberRemoved left = (GroupEvents.MemberRemoved) events.get(0)[1];
        assertThat(left.reason()).isEqualTo("LEFT");
        assertThat(left.actorId()).isEqualTo(me);
        assertThat(left.userId()).isEqualTo(me);
        GroupEvents.RoleChanged promoted = (GroupEvents.RoleChanged) events.get(1)[1];
        assertThat(promoted.actorId()).isNull();
        assertThat(promoted.actorName()).isNull();
        assertThat(promoted.userId()).isEqualTo(bob);
        assertThat(promoted.role()).isEqualTo("ADMIN");
    }

    @Test
    void aTieInJoinTimeIsDecidedByTheSmallerUserId() {
        member(me, ParticipantRole.ADMIN, "2026-11-01T10:00:00Z");
        member(bob, ParticipantRole.MEMBER, "2026-11-02T10:00:00Z");
        member(carol, ParticipantRole.MEMBER, "2026-11-02T10:00:00Z");
        UUID expected = bob.compareTo(carol) < 0 ? bob : carol;

        service.leave(me, group.getId());

        assertThat(roleOf(expected)).isEqualTo(ParticipantRole.ADMIN);
    }

    @Test
    void noPromotionWhenAnotherAdminStaysOrNobodyIsLeft() {
        member(me, ParticipantRole.ADMIN);
        member(bob, ParticipantRole.ADMIN);
        member(carol, ParticipantRole.MEMBER);

        service.leave(me, group.getId());
        assertThat(events).extracting(e -> e[0]).containsExactly(GroupEvents.MEMBER_REMOVED);
        assertThat(roleOf(carol)).isEqualTo(ParticipantRole.MEMBER);

        events.clear();
        service.leave(bob, group.getId());     // carol is still there and becomes admin
        service.leave(carol, group.getId());   // nobody is left: nobody to promote
        assertThat(events).extracting(e -> e[0])
                .containsExactly(GroupEvents.MEMBER_REMOVED, GroupEvents.ROLE_CHANGED, GroupEvents.MEMBER_REMOVED);
    }

    @Test
    void leaveChecksTypeMembershipAndExistence() {
        ConversationParticipant mine = member(me, ParticipantRole.MEMBER);
        mine.leave(Instant.parse("2026-11-02T10:00:00Z"));
        assertCode(() -> service.leave(me, group.getId()), HttpStatus.FORBIDDEN, "NOT_A_MEMBER");

        assertThatThrownBy(() -> service.leave(bob, group.getId())).isInstanceOf(ResourceNotFoundException.class);

        group.setType(ConversationType.PRIVATE);
        mine.setActive(true);
        assertCode(() -> service.leave(me, group.getId()), HttpStatus.BAD_REQUEST, "NOT_A_GROUP");
    }

    // ------------------------------------------------------- name and settings

    @Test
    void membersMayRenameUnlessOnlyAdminsCanEdit() {
        member(me, ParticipantRole.ADMIN);
        member(bob, ParticipantRole.MEMBER);

        service.updateGroup(bob, group.getId(), new UpdateGroupRequest("  New name ", null, null, null));
        assertThat(settings.getName()).isEqualTo("New name");
        GroupEvents.Updated event = onlyEvent(GroupEvents.UPDATED, GroupEvents.Updated.class);
        assertThat(event.name()).isEqualTo("New name");
        assertThat(event.descriptionChanged()).isFalse();
        assertThat(event.onlyAdminsCanSend()).isNull();
        assertThat(event.onlyAdminsCanEdit()).isNull();
        assertThat(event.actorId()).isEqualTo(bob);

        settings.setOnlyAdminsCanEdit(true);
        assertCode(() -> service.updateGroup(bob, group.getId(), new UpdateGroupRequest("Other", null, null, null)),
                HttpStatus.FORBIDDEN, "NOT_GROUP_ADMIN");
        assertCode(() -> service.updateGroup(bob, group.getId(), new UpdateGroupRequest(null, "x", null, null)),
                HttpStatus.FORBIDDEN, "NOT_GROUP_ADMIN");
    }

    @Test
    void settingsAlwaysNeedAnAdminAndOnlyChangesAreReported() {
        member(me, ParticipantRole.ADMIN);
        member(bob, ParticipantRole.MEMBER);

        assertCode(() -> service.updateGroup(bob, group.getId(), new UpdateGroupRequest(null, null, false, null)),
                HttpStatus.FORBIDDEN, "NOT_GROUP_ADMIN");

        service.updateGroup(me, group.getId(), new UpdateGroupRequest("Trip", "", true, false));

        assertThat(settings.isOnlyAdminsCanSend()).isTrue();
        assertThat(settings.getDescription()).isNull();
        GroupEvents.Updated event = onlyEvent(GroupEvents.UPDATED, GroupEvents.Updated.class);
        assertThat(event.name()).isNull();                  // "Trip" was already the name
        assertThat(event.descriptionChanged()).isTrue();    // "" removed the description
        assertThat(event.onlyAdminsCanSend()).isTrue();
        assertThat(event.onlyAdminsCanEdit()).isNull();     // was already false
    }

    @Test
    void anUpdateThatChangesNothingQueuesNoEvent() {
        member(me, ParticipantRole.ADMIN);

        service.updateGroup(me, group.getId(), new UpdateGroupRequest("Trip", "Old description", false, false));

        assertThat(events).isEmpty();
    }

    @Test
    void anEmptyUpdateOrABadNameIs400() {
        member(me, ParticipantRole.ADMIN);

        assertThatThrownBy(() -> service.updateGroup(me, group.getId(), new UpdateGroupRequest(null, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertField(() -> service.updateGroup(me, group.getId(), new UpdateGroupRequest(" ", null, null, null)), "name");
    }

    // ----------------------------------------------------------------- helpers

    private Optional<Conversation> find(UUID id) {
        return group != null && group.getId().equals(id) ? Optional.of(group) : Optional.empty();
    }

    private ConversationParticipant member(UUID userId, ParticipantRole role) {
        return member(userId, role, "2026-11-01T10:00:00Z");
    }

    private ConversationParticipant member(UUID userId, ParticipantRole role, String joinedAt) {
        Instant at = Instant.parse(joinedAt);
        ConversationParticipant row = new ConversationParticipant(group.getId(), userId, role, at);
        rows.add(row);
        periods.add(new MembershipPeriod(group.getId(), userId, at));
        return row;
    }

    private ParticipantRole roleOf(UUID userId) {
        return rows.stream().filter(r -> r.getUserId().equals(userId)).findFirst().orElseThrow().getRole();
    }

    private <T> T onlyEvent(String type, Class<T> payloadType) {
        assertThat(events).hasSize(1);
        assertThat(events.get(0)[0]).isEqualTo(type);
        return payloadType.cast(events.get(0)[1]);
    }

    private static String name(UUID id) {
        return "Name-" + id.toString().substring(0, 4);
    }

    private static void assertCode(Runnable call, HttpStatus status, String errorCode) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getErrorCode()).isEqualTo(errorCode);
        });
    }

    private static void assertField(Runnable call, String field) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FieldValidationException.class,
                e -> assertThat(e.getField()).isEqualTo(field));
    }
}
