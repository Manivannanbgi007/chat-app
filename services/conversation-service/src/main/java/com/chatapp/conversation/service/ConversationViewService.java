package com.chatapp.conversation.service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.chatapp.conversation.client.UserServiceClient;
import com.chatapp.conversation.dto.response.ConversationResponse;
import com.chatapp.conversation.dto.response.GroupInfoResponse;
import com.chatapp.conversation.dto.response.PublicProfileResponse;
import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.entity.ConversationParticipant;
import com.chatapp.conversation.entity.GroupSettings;
import com.chatapp.conversation.exception.ResourceNotFoundException;
import com.chatapp.conversation.mapper.ConversationMapper;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.conversation.repository.GroupSettingsRepository;
import com.chatapp.conversation.repository.ParticipantRepository;
import org.springframework.stereotype.Service;

/**
 * Builds what ONE user sees of their conversations (API_DOCUMENTATION.md section 4.1).
 *
 * It works on a whole list at once: a handful of database queries and ONE request to user-service for all the
 * profiles (split into parts of 100 by the client), however many chats there are.
 *
 * Rules:
 *  - participants are the CURRENT members; for a group I am no longer in, they and the admins are hidden
 *    (I may still read my old messages, but not who is in the group now)
 *  - otherUser is only set for private chats
 *  - a participant user-service does not know is shown as "Unknown user" instead of being left out
 */
@Service
public class ConversationViewService {

    private final ConversationRepository conversationRepository;
    private final ParticipantRepository participantRepository;
    private final GroupSettingsRepository groupSettingsRepository;
    private final UserServiceClient userServiceClient;
    private final ConversationMapper mapper;

    public ConversationViewService(ConversationRepository conversationRepository,
                                   ParticipantRepository participantRepository,
                                   GroupSettingsRepository groupSettingsRepository,
                                   UserServiceClient userServiceClient,
                                   ConversationMapper mapper) {
        this.conversationRepository = conversationRepository;
        this.participantRepository = participantRepository;
        this.groupSettingsRepository = groupSettingsRepository;
        this.userServiceClient = userServiceClient;
        this.mapper = mapper;
    }

    /** One conversation as the viewer sees it. 404 when the viewer was never in it. */
    public ConversationResponse viewOne(UUID viewer, UUID conversationId, Map<UUID, PublicProfileResponse> knownProfiles) {
        ConversationParticipant myRow = participantRepository.findByConversationIdAndUserId(conversationId, viewer)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
        List<ConversationResponse> result = view(viewer, List.of(myRow), knownProfiles);
        if (result.isEmpty()) {
            throw new ResourceNotFoundException("Conversation not found");
        }
        return result.get(0);
    }

    /**
     * The conversations behind the viewer's own participant rows, newest activity first.
     * knownProfiles: profiles the caller already loaded (they are not asked for again).
     */
    public List<ConversationResponse> view(UUID viewer, List<ConversationParticipant> myRows,
                                           Map<UUID, PublicProfileResponse> knownProfiles) {
        if (myRows.isEmpty()) {
            return List.of();
        }
        Map<UUID, ConversationParticipant> myRowByConversation = myRows.stream()
                .collect(Collectors.toMap(ConversationParticipant::getConversationId, Function.identity(), (a, b) -> a));
        List<UUID> conversationIds = List.copyOf(myRowByConversation.keySet());

        List<Conversation> conversations = conversationRepository.findByIdIn(conversationIds);
        Map<UUID, List<ConversationParticipant>> activeByConversation = activeParticipants(conversationIds);
        List<UUID> groupIds = conversations.stream().filter(Conversation::isGroupChat).map(Conversation::getId).toList();
        Map<UUID, GroupSettings> groupById = groupIds.isEmpty() ? Map.of()
                : groupSettingsRepository.findAllById(groupIds).stream()
                        .collect(Collectors.toMap(GroupSettings::getConversationId, Function.identity()));

        // Everybody whose profile is shown, in ONE user-service call.
        Set<UUID> needed = new LinkedHashSet<>();
        for (Conversation conversation : conversations) {
            visibleMembers(conversation, myRowByConversation.get(conversation.getId()), activeByConversation)
                    .forEach(participant -> needed.add(participant.getUserId()));
        }
        Map<UUID, PublicProfileResponse> profiles = new HashMap<>(knownProfiles);
        List<UUID> missing = needed.stream().filter(id -> !profiles.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            userServiceClient.findSummaries(viewer, missing).forEach(profile -> profiles.put(profile.id(), profile));
        }

        return conversations.stream()
                .sorted(Comparator.comparing(Conversation::lastActivityAt).reversed())
                .map(conversation -> build(viewer, conversation, myRowByConversation.get(conversation.getId()),
                        visibleMembers(conversation, myRowByConversation.get(conversation.getId()), activeByConversation),
                        groupById.get(conversation.getId()), profiles))
                .toList();
    }

    private ConversationResponse build(UUID viewer, Conversation conversation, ConversationParticipant myRow,
                                       List<ConversationParticipant> members, GroupSettings settings,
                                       Map<UUID, PublicProfileResponse> profiles) {
        List<PublicProfileResponse> participants = members.stream()
                .map(member -> profileOf(member.getUserId(), profiles))
                .toList();

        PublicProfileResponse otherUser = null;
        GroupInfoResponse group = null;
        if (conversation.isGroupChat()) {
            List<UUID> adminIds = members.stream()
                    .filter(ConversationParticipant::isAdmin)
                    .map(ConversationParticipant::getUserId)
                    .toList();
            group = settings == null ? null : mapper.toGroupInfo(settings, conversation.getCreatedBy(), adminIds);
        } else {
            otherUser = members.stream()
                    .filter(member -> !member.getUserId().equals(viewer))
                    .findFirst()
                    .map(member -> profileOf(member.getUserId(), profiles))
                    .orElse(null);
        }
        return mapper.toResponse(conversation, participants, otherUser, group,
                mapper.toMembership(myRow), mapper.toPreferences(myRow));
    }

    /** The members the viewer may see: everybody while the viewer is a member, nobody after they left. */
    private static List<ConversationParticipant> visibleMembers(Conversation conversation, ConversationParticipant myRow,
                                                                Map<UUID, List<ConversationParticipant>> activeByConversation) {
        if (myRow == null || !myRow.isActive()) {
            return List.of();
        }
        return activeByConversation.getOrDefault(conversation.getId(), List.of());
    }

    /** Current members per conversation, in the order they joined (stable: ties are ordered by user id). */
    private Map<UUID, List<ConversationParticipant>> activeParticipants(List<UUID> conversationIds) {
        Comparator<ConversationParticipant> byJoinTime = Comparator
                .comparing(ConversationParticipant::getJoinedAt)
                .thenComparing(ConversationParticipant::getUserId);
        return participantRepository.findByConversationIdInAndActiveTrue(conversationIds).stream()
                .sorted(byJoinTime)
                .collect(Collectors.groupingBy(ConversationParticipant::getConversationId));
    }

    private static PublicProfileResponse profileOf(UUID userId, Map<UUID, PublicProfileResponse> profiles) {
        return profiles.getOrDefault(userId, PublicProfileResponse.unknown(userId));
    }
}
