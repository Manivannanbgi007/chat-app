package com.chatapp.message.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.chatapp.message.dto.response.MessageDto;
import com.chatapp.message.entity.ConversationMember;
import com.chatapp.message.entity.Message;
import com.chatapp.message.entity.ProcessedEvent;
import com.chatapp.message.enums.MessageType;
import com.chatapp.message.kafka.MessageEvents;
import com.chatapp.message.kafka.producer.OutboxService;
import com.chatapp.message.repository.ConversationMemberRepository;
import com.chatapp.message.repository.MessageRepository;
import com.chatapp.message.repository.ProcessedEventRepository;
import com.chatapp.message.service.SystemMessageContent.UserRef;
import com.chatapp.message.websocket.RealtimeEvent;
import com.chatapp.message.websocket.RealtimePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handles the events of conversation-service (called by ConversationEventConsumer).
 *
 * Each method is ONE transaction that does everything for one event, or nothing:
 *  1. remember the event id (a repeated event does nothing the second time)
 *  2. update our copy of the membership periods (who joined / left, and when)
 *  3. write the SYSTEM message(s) that show the change in the group ("Asha added Ravi"), dated at the time of the
 *     change, plus a MessageCreatedEvent in the outbox (like every other message)
 * After the commit the SYSTEM message is pushed live to the current members (and to the person who was removed
 * or left, so their app shows it too).
 */
@Service
public class GroupEventService {

    private static final Logger log = LoggerFactory.getLogger(GroupEventService.class);

    private final ProcessedEventRepository processedEventRepository;
    private final ConversationMemberRepository memberRepository;
    private final MessageRepository messageRepository;
    private final OutboxService outboxService;
    private final RealtimePublisher realtimePublisher;
    private final MessageViewService viewService;
    private final JsonMapper jsonMapper;

    public GroupEventService(ProcessedEventRepository processedEventRepository,
                             ConversationMemberRepository memberRepository, MessageRepository messageRepository,
                             OutboxService outboxService, RealtimePublisher realtimePublisher,
                             MessageViewService viewService, JsonMapper jsonMapper) {
        this.processedEventRepository = processedEventRepository;
        this.memberRepository = memberRepository;
        this.messageRepository = messageRepository;
        this.outboxService = outboxService;
        this.realtimePublisher = realtimePublisher;
        this.viewService = viewService;
        this.jsonMapper = jsonMapper;
    }

    /** A new conversation. Everybody joined at createdAt. Groups also get a "created the group" message. */
    @Transactional
    public void conversationCreated(UUID eventId, UUID conversationId, String type, List<UUID> participantIds,
                                    Instant createdAt, UserRef creator, String groupName) {
        if (!markProcessed(eventId)) {
            return;
        }
        Instant time = micros(createdAt);
        for (UUID userId : participantIds) {
            addPeriod(conversationId, userId, type, time);
        }
        if (ConversationMember.GROUP.equals(type)) {
            new SystemMessages(eventId, conversationId, time)
                    .write(creator.id(), SystemMessageContent.groupCreated(creator, groupName), Set.of());
        }
    }

    @Transactional
    public void membersAdded(UUID eventId, UUID conversationId, UserRef actor, List<UserRef> members, Instant joinedAt) {
        if (!markProcessed(eventId)) {
            return;
        }
        Instant time = micros(joinedAt);
        for (UserRef member : members) {
            addPeriod(conversationId, member.id(), ConversationMember.GROUP, time);
        }
        new SystemMessages(eventId, conversationId, time)
                .write(actor.id(), SystemMessageContent.membersAdded(actor, members), Set.of());
    }

    /** left = true: the user left by themselves (then actor = user); false: an admin removed them. */
    @Transactional
    public void memberRemoved(UUID eventId, UUID conversationId, UserRef actor, UserRef user, boolean left, Instant leftAt) {
        if (!markProcessed(eventId)) {
            return;
        }
        Instant time = micros(leftAt);
        int closed = memberRepository.closeOpenPeriods(conversationId, user.id(), time);
        if (closed == 0) {
            log.debug("No open membership period to close in conversation {}", conversationId);
        }
        Map<String, Object> content = left
                ? SystemMessageContent.memberLeft(actor)
                : SystemMessageContent.memberRemoved(actor, user);
        // the removed person is no longer a member, but should still see why the group stopped
        new SystemMessages(eventId, conversationId, time).write(actor.id(), content, Set.of(user.id()));
    }

    /** actor null = automatic: the last admin left and the earliest member became admin. */
    @Transactional
    public void roleChanged(UUID eventId, UUID conversationId, UserRef actor, UserRef user, boolean admin, Instant at) {
        if (!markProcessed(eventId)) {
            return;
        }
        Map<String, Object> content = admin
                ? SystemMessageContent.adminAdded(actor, user)
                : SystemMessageContent.adminRemoved(actor, user);
        UUID sender = actor != null ? actor.id() : user.id();
        new SystemMessages(eventId, conversationId, micros(at)).write(sender, content, Set.of());
    }

    /** One SYSTEM message per thing that changed (name, description, each setting). */
    @Transactional
    public void groupUpdated(UUID eventId, UUID conversationId, UserRef actor, Instant at, String newName,
                             boolean descriptionChanged, Boolean onlyAdminsCanSend, Boolean onlyAdminsCanEdit) {
        if (!markProcessed(eventId)) {
            return;
        }
        SystemMessages messages = new SystemMessages(eventId, conversationId, micros(at));
        if (newName != null) {
            messages.write(actor.id(), SystemMessageContent.nameChanged(actor, newName), Set.of());
        }
        if (descriptionChanged) {
            messages.write(actor.id(), SystemMessageContent.descriptionChanged(actor), Set.of());
        }
        if (onlyAdminsCanSend != null) {
            messages.write(actor.id(), SystemMessageContent.settingChanged(actor,
                    SystemMessageContent.ONLY_ADMINS_CAN_SEND, onlyAdminsCanSend), Set.of());
        }
        if (onlyAdminsCanEdit != null) {
            messages.write(actor.id(), SystemMessageContent.settingChanged(actor,
                    SystemMessageContent.ONLY_ADMINS_CAN_EDIT, onlyAdminsCanEdit), Set.of());
        }
    }

    // ----------------------------------------------------------------- helpers

    /** False when the event was already handled. */
    private boolean markProcessed(UUID eventId) {
        if (processedEventRepository.existsById(eventId)) {
            log.info("Event {} was already processed, skipping", eventId);
            return false;
        }
        processedEventRepository.save(new ProcessedEvent(eventId));
        return true;
    }

    private void addPeriod(UUID conversationId, UUID userId, String type, Instant joinedAt) {
        // insert-ignore: the same period may already be known from one of conversation-service's answers
        memberRepository.insertIgnore(UUID.randomUUID().toString(), conversationId.toString(), userId.toString(),
                type, joinedAt, null);
    }

    private static Instant micros(Instant time) {
        return time.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Writes the SYSTEM messages of one event. They all get the time of the change; the idempotency key
     * "sys-<eventId>-<n>" is unique per message, so even without processed_events a message could never be
     * saved twice.
     */
    private class SystemMessages {
        private final UUID eventId;
        private final UUID conversationId;
        private final Instant time;
        private int count;

        SystemMessages(UUID eventId, UUID conversationId, Instant time) {
            this.eventId = eventId;
            this.conversationId = conversationId;
            this.time = time;
        }

        void write(UUID senderId, Map<String, Object> content, Collection<UUID> alsoTell) {
            count++;
            Message message = new Message();
            message.setConversationId(conversationId);
            message.setSenderId(senderId);
            message.setType(MessageType.SYSTEM);
            message.setContent(jsonMapper.writeValueAsString(content));
            message.setIdempotencyKey("sys-" + eventId + "-" + count);
            message.setCreatedAt(time);
            messageRepository.saveAndFlush(message);

            List<UUID> members = memberRepository.findCurrentMemberIds(conversationId);
            List<UUID> recipients = members.stream().filter(id -> !id.equals(senderId)).toList();
            outboxService.queue("MessageCreatedEvent", conversationId, new MessageEvents.MessageCreated(
                    message.getId(), conversationId, senderId, MessageType.SYSTEM.name(), time, recipients));

            Set<UUID> pushTo = new LinkedHashSet<>(members);
            pushTo.addAll(alsoTell);
            RealtimeEvent<MessageDto> event = RealtimeEvent.of("MESSAGE_CREATED", conversationId, viewService.toLiveDto(message));
            List<UUID> targets = new ArrayList<>(pushTo);
            RealtimePublisher.afterCommit(() -> realtimePublisher.toUsers(targets, "/queue/messages", event));
        }
    }
}
