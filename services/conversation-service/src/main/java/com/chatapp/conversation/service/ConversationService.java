package com.chatapp.conversation.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import com.chatapp.conversation.exception.BadRequestException;
import com.chatapp.conversation.exception.ForbiddenException;
import com.chatapp.conversation.exception.ResourceNotFoundException;
import com.chatapp.conversation.kafka.producer.ConversationCreatedPayload;
import com.chatapp.conversation.kafka.producer.OutboxService;
import com.chatapp.conversation.mapper.ConversationMapper;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.conversation.repository.GroupSettingsRepository;
import com.chatapp.conversation.repository.MembershipPeriodRepository;
import com.chatapp.conversation.repository.ParticipantRepository;
import com.chatapp.conversation.repository.PrivateKeyRepository;
import com.chatapp.conversation.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creating one-to-one conversations, reading the chat list, and the internal answer for other services.
 * (Groups are created and managed by GroupService; mute / archive / pin by PreferenceService.)
 *
 * Rule for creating: ask user-service FIRST (user exists? blocked?), then open the database
 * transaction. We never hold a database connection while waiting for another service.
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    /** The answer of create: the conversation and whether this call created it (201) or found it (200). */
    public record CreateResult(ConversationResponse conversation, boolean created) {
    }

    private final ConversationRepository conversationRepository;
    private final ParticipantRepository participantRepository;
    private final PrivateKeyRepository privateKeyRepository;
    private final MembershipPeriodRepository periodRepository;
    private final GroupSettingsRepository groupSettingsRepository;
    private final UserServiceClient userServiceClient;
    private final OutboxService outboxService;
    private final ConversationMapper mapper;
    private final ConversationViewService viewService;
    private final GroupService groupService;
    private final TransactionTemplate transactionTemplate;

    public ConversationService(ConversationRepository conversationRepository,
                               ParticipantRepository participantRepository,
                               PrivateKeyRepository privateKeyRepository,
                               MembershipPeriodRepository periodRepository,
                               GroupSettingsRepository groupSettingsRepository,
                               UserServiceClient userServiceClient,
                               OutboxService outboxService,
                               ConversationMapper mapper,
                               ConversationViewService viewService,
                               GroupService groupService,
                               PlatformTransactionManager transactionManager) {
        this.conversationRepository = conversationRepository;
        this.participantRepository = participantRepository;
        this.privateKeyRepository = privateKeyRepository;
        this.periodRepository = periodRepository;
        this.groupSettingsRepository = groupSettingsRepository;
        this.userServiceClient = userServiceClient;
        this.outboxService = outboxService;
        this.mapper = mapper;
        this.viewService = viewService;
        this.groupService = groupService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ create

    public CreateResult create(UUID me, CreateConversationRequest request) {
        if (request.type() == ConversationType.GROUP) {
            return new CreateResult(groupService.createGroup(me, request), true);
        }
        UUID other = request.userId();
        if (other == null) {
            throw new BadRequestException("userId is required for a private conversation");
        }
        if (other.equals(me)) {
            throw new BadRequestException("You cannot start a conversation with yourself");
        }

        // 1. Questions to user-service (no database transaction is open yet).
        Map<UUID, PublicProfileResponse> profiles = indexById(userServiceClient.findSummaries(me, List.of(me, other)));
        if (!profiles.containsKey(other)) {
            throw new ResourceNotFoundException("User not found");
        }
        if (userServiceClient.isBlockedEitherWay(me, other)) {
            // Same answer whoever blocked whom, so the caller learns nothing extra.
            throw new ForbiddenException("You cannot message this user", "MESSAGING_NOT_ALLOWED");
        }

        // 2. Find or create the one conversation of this pair.
        Created result = findOrCreatePrivate(me, other);
        ConversationResponse response = viewService.viewOne(me, result.conversation().getId(), profiles);
        return new CreateResult(response, result.created());
    }

    private record Created(Conversation conversation, boolean created) {
    }

    private Created findOrCreatePrivate(UUID me, UUID other) {
        UUID low = PrivateConversationKey.lowerOf(me, other);
        UUID high = PrivateConversationKey.higherOf(me, other);

        Conversation existing = findExisting(low, high);
        if (existing != null) {
            return new Created(existing, false);
        }
        try {
            Conversation created = transactionTemplate.execute(status -> createPrivateInTransaction(me, other));
            return new Created(created, true);
        } catch (DataIntegrityViolationException e) {
            // Another request created the same pair at the same moment: the unique key stopped us. Use theirs.
            log.info("Private conversation was created concurrently, returning the existing one");
            Conversation winner = findExisting(low, high);
            if (winner == null) {
                throw e;
            }
            return new Created(winner, false);
        }
    }

    private Conversation findExisting(UUID low, UUID high) {
        return privateKeyRepository.findByUserLowIdAndUserHighId(low, high)
                .flatMap(key -> conversationRepository.findById(key.getConversationId()))
                .orElse(null);
    }

    /**
     * Everything that must succeed or fail together: conversation, both participants, their membership periods,
     * the pair key and the event. Every row uses the same time, so both users joined exactly at createdAt.
     */
    private Conversation createPrivateInTransaction(UUID me, UUID other) {
        Instant now = Times.now();
        Conversation conversation = conversationRepository.save(new Conversation(ConversationType.PRIVATE, me, now));
        for (UUID userId : List.of(me, other)) {
            participantRepository.save(new ConversationParticipant(conversation.getId(), userId, ParticipantRole.MEMBER, now));
            periodRepository.save(new MembershipPeriod(conversation.getId(), userId, now));
        }
        // saveAndFlush: a duplicate pair fails right here, inside the transaction
        privateKeyRepository.saveAndFlush(new PrivateConversationKey(conversation.getId(), me, other));

        outboxService.queue("ConversationCreatedEvent", conversation.getId(),
                new ConversationCreatedPayload(conversation.getId(), conversation.getType(),
                        List.of(me, other), now, me, null, null));
        return conversation;
    }

    // -------------------------------------------------------------------- read

    /** The caller's chat list (also archived chats and groups they left), newest activity first. */
    public List<ConversationResponse> list(UUID me) {
        return viewService.view(me, participantRepository.findByUserId(me), Map.of());
    }

    /** One conversation. Unknown id and "never a participant" give the same 404 (nobody can probe for ids). */
    public ConversationResponse get(UUID me, UUID conversationId) {
        return viewService.viewOne(me, conversationId, Map.of());
    }

    /**
     * For other services: who takes part now, who are admins, may members send, and every membership period.
     * No user-level privacy here, the caller is a trusted service.
     */
    public InternalConversationResponse getForInternalUse(UUID conversationId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
        List<ConversationParticipant> active = participantRepository.findByConversationIdAndActiveTrue(conversationId)
                .stream()
                .sorted(java.util.Comparator.comparing(ConversationParticipant::getJoinedAt)
                        .thenComparing(ConversationParticipant::getUserId))
                .toList();
        List<UUID> participantIds = active.stream().map(ConversationParticipant::getUserId).toList();
        List<UUID> adminIds = conversation.isGroupChat()
                ? active.stream().filter(ConversationParticipant::isAdmin).map(ConversationParticipant::getUserId).toList()
                : List.of();
        boolean onlyAdminsCanSend = conversation.isGroupChat() && groupSettingsRepository.findById(conversationId)
                .map(GroupSettings::isOnlyAdminsCanSend)
                .orElse(false);
        String name = conversation.isGroupChat()
                ? groupSettingsRepository.findById(conversationId).map(GroupSettings::getName).orElse(null)
                : null;
        List<MembershipPeriod> periods = periodRepository.findByConversationIdOrderByJoinedAtAscUserIdAsc(conversationId);
        return mapper.toInternal(conversation, name, participantIds, adminIds, onlyAdminsCanSend, mapper.toPeriods(periods));
    }

    /**
     * For notification-service: the current members whose mute is switched on right now
     * (a mute is active while mutedUntil is in the future). 404 if the conversation does not exist.
     */
    public List<UUID> getMutedUserIdsForInternalUse(UUID conversationId) {
        if (!conversationRepository.existsById(conversationId)) {
            throw new ResourceNotFoundException("Conversation not found");
        }
        Instant now = Instant.now();
        return participantRepository.findByConversationIdAndActiveTrue(conversationId).stream()
                .filter(participant -> participant.getMutedUntil() != null && participant.getMutedUntil().isAfter(now))
                .map(ConversationParticipant::getUserId)
                .sorted()
                .toList();
    }

    private static Map<UUID, PublicProfileResponse> indexById(List<PublicProfileResponse> profiles) {
        Map<UUID, PublicProfileResponse> byId = new HashMap<>();
        profiles.forEach(profile -> byId.put(profile.id(), profile));
        return byId;
    }
}
