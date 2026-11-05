package com.chatapp.conversation.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

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
import com.chatapp.conversation.exception.BadRequestException;
import com.chatapp.conversation.exception.ConflictException;
import com.chatapp.conversation.exception.FieldValidationException;
import com.chatapp.conversation.exception.ForbiddenException;
import com.chatapp.conversation.exception.GroupAddNotAllowedException;
import com.chatapp.conversation.exception.ResourceNotFoundException;
import com.chatapp.conversation.kafka.producer.ConversationCreatedPayload;
import com.chatapp.conversation.kafka.producer.GroupEvents;
import com.chatapp.conversation.kafka.producer.OutboxService;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.conversation.repository.GroupSettingsRepository;
import com.chatapp.conversation.repository.MembershipPeriodRepository;
import com.chatapp.conversation.repository.ParticipantRepository;
import com.chatapp.conversation.util.Times;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creating and managing groups: name and settings, adding and removing members, admins, leaving.
 *
 * How every change works (the same three steps each time):
 *  1. Checks that need only our database, then the questions to user-service (may these people be added? what
 *     are their names?). No database transaction is open while we wait for user-service.
 *  2. ONE transaction that first LOCKS the conversation row, reads the members again (another admin may have
 *     changed something in the meantime), checks the rules again, changes the rows and queues the Kafka event.
 *     The lock makes two changes of the same group run one after the other.
 *  3. Builds the answer: the conversation as the caller sees it now.
 *
 * Rules (API_DOCUMENTATION.md 4.3): the creator is ADMIN; admins add / remove members, change roles and the
 * settings; name and description need an admin only when onlyAdminsCanEdit; when the last admin leaves, the
 * member who joined earliest becomes admin. Every change becomes a SYSTEM message in message-service.
 */
@Service
public class GroupService {

    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_DESCRIPTION_LENGTH = 500;

    private final ConversationRepository conversationRepository;
    private final ParticipantRepository participantRepository;
    private final GroupSettingsRepository groupSettingsRepository;
    private final MembershipPeriodRepository periodRepository;
    private final UserServiceClient userServiceClient;
    private final OutboxService outboxService;
    private final ConversationViewService viewService;
    private final TransactionTemplate transactionTemplate;
    private final int maxMembers;

    public GroupService(ConversationRepository conversationRepository,
                        ParticipantRepository participantRepository,
                        GroupSettingsRepository groupSettingsRepository,
                        MembershipPeriodRepository periodRepository,
                        UserServiceClient userServiceClient,
                        OutboxService outboxService,
                        ConversationViewService viewService,
                        PlatformTransactionManager transactionManager,
                        @Value("${chatapp.groups.max-members:256}") int maxMembers) {
        this.conversationRepository = conversationRepository;
        this.participantRepository = participantRepository;
        this.groupSettingsRepository = groupSettingsRepository;
        this.periodRepository = periodRepository;
        this.userServiceClient = userServiceClient;
        this.outboxService = outboxService;
        this.viewService = viewService;
        this.maxMembers = maxMembers;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // Every read inside a change sees the latest committed data (the conversation lock orders the changes).
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * The group as one change sees it: the conversation, my row, and the current members.
     * Inside a transaction it is read AFTER locking the conversation, so it is up to date.
     */
    private record Snapshot(Conversation conversation, ConversationParticipant me, List<ConversationParticipant> active) {

        Optional<ConversationParticipant> activeRow(UUID userId) {
            return active.stream().filter(p -> p.getUserId().equals(userId)).findFirst();
        }

        List<UUID> activeIds() {
            return active.stream().map(ConversationParticipant::getUserId).toList();
        }
    }

    // ------------------------------------------------------------------ create

    public ConversationResponse createGroup(UUID me, CreateConversationRequest request) {
        String name = cleanName(request.name());
        String description = cleanDescription(request.description());
        Set<UUID> members = new LinkedHashSet<>(request.memberIds() == null ? List.of() : request.memberIds());
        members.remove(null);
        members.remove(me);                         // I am always in my own group, as admin
        if (members.isEmpty()) {
            throw new FieldValidationException("memberIds", "Add at least one other member");
        }
        if (members.size() + 1 > maxMembers) {
            throw groupFull();
        }
        List<UUID> memberIds = List.copyOf(members);
        requireAllMayBeAdded(me, memberIds);
        List<UUID> everyone = new ArrayList<>();
        everyone.add(me);
        everyone.addAll(memberIds);
        Map<UUID, PublicProfileResponse> profiles = profilesOf(me, everyone);

        Conversation created = inTransaction(() -> {
            Instant now = Times.now();
            Conversation conversation = conversationRepository.save(new Conversation(ConversationType.GROUP, me, now));
            for (UUID userId : everyone) {
                ParticipantRole role = userId.equals(me) ? ParticipantRole.ADMIN : ParticipantRole.MEMBER;
                participantRepository.save(new ConversationParticipant(conversation.getId(), userId, role, now));
                periodRepository.save(new MembershipPeriod(conversation.getId(), userId, now));
            }
            groupSettingsRepository.save(new GroupSettings(conversation.getId(), name, description, now));
            outboxService.queue("ConversationCreatedEvent", conversation.getId(), new ConversationCreatedPayload(
                    conversation.getId(), ConversationType.GROUP, everyone, now, me, name, nameOf(me, profiles)));
            return conversation;
        });
        return viewService.viewOne(me, created.getId(), profiles);
    }

    // ------------------------------------------------------- name and settings

    public ConversationResponse updateGroup(UUID me, UUID conversationId, UpdateGroupRequest request) {
        if (request.isEmpty()) {
            throw new BadRequestException("Send at least one field to change");
        }
        String newName = request.name() == null ? null : cleanName(request.name());
        boolean descriptionGiven = request.description() != null;
        String newDescription = descriptionGiven ? cleanDescription(request.description()) : null;

        Snapshot before = snapshot(me, conversationId, false);
        checkMayUpdate(before, requireSettings(conversationId), request, newName != null || descriptionGiven);
        Map<UUID, PublicProfileResponse> profiles = profilesOf(me, List.of(me));

        inTransaction(() -> {
            Snapshot now = snapshot(me, conversationId, true);
            GroupSettings settings = requireSettings(conversationId);
            checkMayUpdate(now, settings, request, newName != null || descriptionGiven);

            String changedName = newName != null && !newName.equals(settings.getName()) ? newName : null;
            boolean descriptionChanged = descriptionGiven && !Objects.equals(newDescription, settings.getDescription());
            Boolean changedSend = changed(request.onlyAdminsCanSend(), settings.isOnlyAdminsCanSend());
            Boolean changedEdit = changed(request.onlyAdminsCanEdit(), settings.isOnlyAdminsCanEdit());
            if (changedName == null && !descriptionChanged && changedSend == null && changedEdit == null) {
                return null;                        // nothing really changed: no event, no SYSTEM message
            }

            Instant at = Times.now();
            if (changedName != null) {
                settings.setName(changedName);
            }
            if (descriptionChanged) {
                settings.setDescription(newDescription);
            }
            if (changedSend != null) {
                settings.setOnlyAdminsCanSend(changedSend);
            }
            if (changedEdit != null) {
                settings.setOnlyAdminsCanEdit(changedEdit);
            }
            settings.setUpdatedAt(at);
            groupSettingsRepository.save(settings);
            outboxService.queue(GroupEvents.UPDATED, conversationId, new GroupEvents.Updated(conversationId, me,
                    nameOf(me, profiles), at, changedName, descriptionChanged, changedSend, changedEdit));
            return null;
        });
        return viewService.viewOne(me, conversationId, profiles);
    }

    /** Settings always need an admin; name and description only when the group says "only admins can edit". */
    private static void checkMayUpdate(Snapshot snapshot, GroupSettings settings, UpdateGroupRequest request,
                                       boolean changesInfo) {
        boolean changesSettings = request.onlyAdminsCanSend() != null || request.onlyAdminsCanEdit() != null;
        if (changesSettings && !snapshot.me().isAdmin()) {
            throw notAdmin();
        }
        if (changesInfo && settings.isOnlyAdminsCanEdit() && !snapshot.me().isAdmin()) {
            throw notAdmin();
        }
    }

    /** The new value if it differs from the current one, otherwise null ("unchanged"). */
    private static Boolean changed(Boolean requested, boolean current) {
        return requested != null && requested != current ? requested : null;
    }

    // ----------------------------------------------------------------- members

    public ConversationResponse addMembers(UUID me, UUID conversationId, AddMembersRequest request) {
        Snapshot before = snapshot(me, conversationId, false);
        requireAdmin(before);
        List<UUID> candidates = newcomers(before, request.userIds(), me);
        if (candidates.isEmpty()) {
            throw new ConflictException("Everyone is already a member of this group", "ALREADY_MEMBER");
        }
        if (before.active().size() + candidates.size() > maxMembers) {
            throw groupFull();
        }
        requireAllMayBeAdded(me, candidates);
        List<UUID> wanted = new ArrayList<>(before.activeIds());
        wanted.addAll(candidates);
        Map<UUID, PublicProfileResponse> profiles = profilesOf(me, wanted);

        inTransaction(() -> {
            Snapshot now = snapshot(me, conversationId, true);
            requireAdmin(now);
            // Somebody may have added some of them in the meantime: only the ones still missing.
            List<UUID> toAdd = newcomers(now, candidates, me);
            if (toAdd.isEmpty()) {
                throw new ConflictException("Everyone is already a member of this group", "ALREADY_MEMBER");
            }
            if (now.active().size() + toAdd.size() > maxMembers) {
                throw groupFull();
            }
            Instant at = Times.now();
            List<GroupEvents.NamedUser> added = new ArrayList<>();
            for (UUID userId : toAdd) {
                ConversationParticipant row = participantRepository.findByConversationIdAndUserId(conversationId, userId)
                        .map(existing -> {
                            existing.rejoin(at);    // was a member before: the same row starts a new period
                            return existing;
                        })
                        .orElseGet(() -> new ConversationParticipant(conversationId, userId, ParticipantRole.MEMBER, at));
                participantRepository.save(row);
                periodRepository.save(new MembershipPeriod(conversationId, userId, at));
                added.add(new GroupEvents.NamedUser(userId, nameOf(userId, profiles)));
            }
            outboxService.queue(GroupEvents.MEMBERS_ADDED, conversationId, new GroupEvents.MembersAdded(
                    conversationId, me, nameOf(me, profiles), added, at));
            return null;
        });
        return viewService.viewOne(me, conversationId, profiles);
    }

    public ConversationResponse removeMember(UUID me, UUID conversationId, UUID userId) {
        Snapshot before = snapshot(me, conversationId, false);
        checkMayChangeMember(before, me, userId);
        List<UUID> wanted = new ArrayList<>(before.activeIds());
        Map<UUID, PublicProfileResponse> profiles = profilesOf(me, wanted);

        inTransaction(() -> {
            Snapshot now = snapshot(me, conversationId, true);
            ConversationParticipant target = checkMayChangeMember(now, me, userId);
            Instant at = Times.now();
            target.leave(at);
            participantRepository.save(target);
            closePeriods(conversationId, userId, at);
            outboxService.queue(GroupEvents.MEMBER_REMOVED, conversationId, new GroupEvents.MemberRemoved(
                    conversationId, me, nameOf(me, profiles), userId, nameOf(userId, profiles), "REMOVED", at));
            return null;
        });
        return viewService.viewOne(me, conversationId, profiles);
    }

    public ConversationResponse changeRole(UUID me, UUID conversationId, UUID userId, ParticipantRole role) {
        Snapshot before = snapshot(me, conversationId, false);
        checkMayChangeMember(before, me, userId);
        Map<UUID, PublicProfileResponse> profiles = profilesOf(me, before.activeIds());

        inTransaction(() -> {
            Snapshot now = snapshot(me, conversationId, true);
            ConversationParticipant target = checkMayChangeMember(now, me, userId);
            if (target.getRole() == role) {
                return null;                        // already so: no event
            }
            Instant at = Times.now();
            target.setRole(role);
            participantRepository.save(target);
            outboxService.queue(GroupEvents.ROLE_CHANGED, conversationId, new GroupEvents.RoleChanged(
                    conversationId, me, nameOf(me, profiles), userId, nameOf(userId, profiles), role.name(), at));
            return null;
        });
        return viewService.viewOne(me, conversationId, profiles);
    }

    /**
     * I leave the group. If I was the last admin and others stay, the member who joined earliest becomes admin.
     * The answer is the group as I see it now (read-only, membership.active = false).
     */
    public ConversationResponse leave(UUID me, UUID conversationId) {
        Snapshot before = snapshot(me, conversationId, false);
        Map<UUID, PublicProfileResponse> profiles = profilesOf(me, before.activeIds());

        inTransaction(() -> {
            Snapshot now = snapshot(me, conversationId, true);
            ConversationParticipant mine = now.me();
            boolean wasAdmin = mine.isAdmin();
            Instant at = Times.now();
            mine.leave(at);
            participantRepository.save(mine);
            closePeriods(conversationId, me, at);
            String myName = nameOf(me, profiles);
            outboxService.queue(GroupEvents.MEMBER_REMOVED, conversationId, new GroupEvents.MemberRemoved(
                    conversationId, me, myName, me, myName, "LEFT", at));

            List<ConversationParticipant> remaining = now.active().stream()
                    .filter(p -> !p.getUserId().equals(me))
                    .toList();
            boolean adminLeft = remaining.stream().anyMatch(ConversationParticipant::isAdmin);
            if (wasAdmin && !adminLeft && !remaining.isEmpty()) {
                ConversationParticipant successor = remaining.stream()
                        .min(Comparator.comparing(ConversationParticipant::getJoinedAt)
                                .thenComparing(ConversationParticipant::getUserId))
                        .orElseThrow();
                successor.setRole(ParticipantRole.ADMIN);
                participantRepository.save(successor);
                // queued AFTER the "left" event, so the SYSTEM messages appear in this order
                outboxService.queue(GroupEvents.ROLE_CHANGED, conversationId, new GroupEvents.RoleChanged(
                        conversationId, null, null, successor.getUserId(), nameOf(successor.getUserId(), profiles),
                        ParticipantRole.ADMIN.name(), at));
            }
            return null;
        });
        return viewService.viewOne(me, conversationId, profiles);
    }

    // ----------------------------------------------------------------- checks

    /**
     * Reads the group as seen by me and checks that: I was ever in it (404, the same answer as an unknown id),
     * it is a group (400 NOT_A_GROUP), and I am still a member (403 NOT_A_MEMBER).
     * With lock = true (inside a transaction) the conversation row is locked FIRST, then everything is read.
     */
    private Snapshot snapshot(UUID me, UUID conversationId, boolean lock) {
        Conversation conversation = (lock
                ? conversationRepository.findByIdForUpdate(conversationId)
                : conversationRepository.findById(conversationId))
                .orElseThrow(GroupService::conversationNotFound);
        ConversationParticipant mine = participantRepository.findByConversationIdAndUserId(conversationId, me)
                .orElseThrow(GroupService::conversationNotFound);
        if (!conversation.isGroupChat()) {
            throw new BadRequestException("This conversation is not a group", "NOT_A_GROUP");
        }
        if (!mine.isActive()) {
            throw new ForbiddenException("You are no longer a member of this group", "NOT_A_MEMBER");
        }
        return new Snapshot(conversation, mine, participantRepository.findByConversationIdAndActiveTrue(conversationId));
    }

    /** Remove or change the role of another member: not myself, I must be admin, they must be a current member. */
    private static ConversationParticipant checkMayChangeMember(Snapshot snapshot, UUID me, UUID userId) {
        if (userId.equals(me)) {
            throw new BadRequestException("You cannot do this to yourself (use leave instead)", "CANNOT_CHANGE_SELF");
        }
        requireAdmin(snapshot);
        return snapshot.activeRow(userId)
                .orElseThrow(() -> new ResourceNotFoundException("This person is not a member of the group"));
    }

    private static void requireAdmin(Snapshot snapshot) {
        if (!snapshot.me().isAdmin()) {
            throw notAdmin();
        }
    }

    /** The requested users who are not current members yet (no duplicates, never me), in request order. */
    private static List<UUID> newcomers(Snapshot snapshot, List<UUID> requested, UUID me) {
        Set<UUID> current = new java.util.HashSet<>(snapshot.activeIds());
        Set<UUID> result = new LinkedHashSet<>();
        for (UUID userId : requested) {
            if (userId != null && !userId.equals(me) && !current.contains(userId)) {
                result.add(userId);
            }
        }
        return List.copyOf(result);
    }

    private void requireAllMayBeAdded(UUID me, List<UUID> userIds) {
        List<UUID> refused = userServiceClient.groupAddNotAllowed(me, userIds);
        if (!refused.isEmpty()) {
            throw new GroupAddNotAllowedException(refused);
        }
    }

    private GroupSettings requireSettings(UUID conversationId) {
        return groupSettingsRepository.findById(conversationId)
                .orElseThrow(GroupService::conversationNotFound);
    }

    private void closePeriods(UUID conversationId, UUID userId, Instant at) {
        for (MembershipPeriod period : periodRepository.findByConversationIdAndUserIdAndLeftAtIsNull(conversationId, userId)) {
            period.setLeftAt(at);
            periodRepository.save(period);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String cleanName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new FieldValidationException("name", "The group needs a name");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new FieldValidationException("name", "The name can have at most " + MAX_NAME_LENGTH + " characters");
        }
        return trimmed;
    }

    /** Empty means "no description" (null). */
    private static String cleanDescription(String description) {
        String trimmed = description == null ? "" : description.trim();
        if (trimmed.length() > MAX_DESCRIPTION_LENGTH) {
            throw new FieldValidationException("description",
                    "The description can have at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Profiles for the events (names) and for the answer, in one user-service call before any transaction. */
    private Map<UUID, PublicProfileResponse> profilesOf(UUID viewer, List<UUID> userIds) {
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(userIds));
        if (!ids.contains(viewer)) {
            ids.add(0, viewer);
        }
        Map<UUID, PublicProfileResponse> byId = new HashMap<>();
        userServiceClient.findSummaries(viewer, ids).forEach(profile -> byId.put(profile.id(), profile));
        return byId;
    }

    /** The display name for a SYSTEM message, or null when user-service did not return the person. */
    private static String nameOf(UUID userId, Map<UUID, PublicProfileResponse> profiles) {
        PublicProfileResponse profile = profiles.get(userId);
        return profile == null ? null : profile.displayName();
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transactionTemplate.execute(status -> work.get());
    }

    private static ResourceNotFoundException conversationNotFound() {
        return new ResourceNotFoundException("Conversation not found");
    }

    private static ForbiddenException notAdmin() {
        return new ForbiddenException("Only group admins can do this", "NOT_GROUP_ADMIN");
    }

    private ConflictException groupFull() {
        return new ConflictException("A group can have at most " + maxMembers + " members", "GROUP_FULL");
    }
}
