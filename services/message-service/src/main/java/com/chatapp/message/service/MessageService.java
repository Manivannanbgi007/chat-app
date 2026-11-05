package com.chatapp.message.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.chatapp.message.client.ConversationClient.ConversationInfo;
import com.chatapp.message.client.MediaClient;
import com.chatapp.message.client.UserClient;
import com.chatapp.message.config.AppProperties;
import com.chatapp.message.dto.request.SendMessageRequest;
import com.chatapp.message.dto.response.ConversationSummaryDto;
import com.chatapp.message.dto.response.MessageDto;
import com.chatapp.message.dto.response.PageDto;
import com.chatapp.message.dto.response.SendResult;
import com.chatapp.message.entity.Message;
import com.chatapp.message.entity.MessageAttachment;
import com.chatapp.message.enums.MessageType;
import com.chatapp.message.exception.BadRequestException;
import com.chatapp.message.exception.ForbiddenException;
import com.chatapp.message.kafka.MessageEvents;
import com.chatapp.message.kafka.producer.OutboxService;
import com.chatapp.message.repository.ConversationMemberRepository;
import com.chatapp.message.repository.MessageAttachmentRepository;
import com.chatapp.message.repository.MessageRepository;
import com.chatapp.message.util.Cursor;
import com.chatapp.message.websocket.RealtimeEvent;
import com.chatapp.message.websocket.RealtimePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The heart of the product: save a message safely, and read conversations back in order.
 *
 * Sending, step by step:
 *  1. Same idempotency key already used by this sender? Return the saved message (retries never duplicate).
 *  2. Ask conversation-service: is the sender a CURRENT member? (404 if not, also for former group members)
 *  3. Group with "only admins can send": is the sender an admin? (403 ONLY_ADMINS_CAN_SEND if not)
 *  4. Private chat only: ask user-service whether either user blocked the other (403 if so).
 *     Blocking does not apply inside groups (as in WhatsApp).
 *  5. In ONE database transaction: save the message and queue the Kafka event (outbox).
 *  6. After the commit: push the message live to every connected device of every current member.
 * The caller gets the answer only after step 5, so an acknowledged message is always saved.
 *
 * Reading (history, sync, summaries) only ever returns messages from the reader's membership periods
 * (see MessageRepository.VISIBLE_TO_USER): the removed-member rule.
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);
    private static final int DEFAULT_PAGE_SIZE = 30;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_CONVERSATIONS_IN_SUMMARY = 200;

    private final MessageRepository messageRepository;
    private final MessageAttachmentRepository attachmentRepository;
    private final ConversationMemberRepository memberRepository;
    private final ConversationAccessService accessService;
    private final MessageViewService viewService;
    private final UserClient userClient;
    private final MediaClient mediaClient;
    private final OutboxService outboxService;
    private final RealtimePublisher realtimePublisher;
    private final TransactionTemplate transactionTemplate;
    private final AppProperties.Messages rules;

    public MessageService(MessageRepository messageRepository, MessageAttachmentRepository attachmentRepository,
                          ConversationMemberRepository memberRepository, ConversationAccessService accessService,
                          MessageViewService viewService, UserClient userClient, MediaClient mediaClient,
                          OutboxService outboxService, RealtimePublisher realtimePublisher,
                          TransactionTemplate transactionTemplate, AppProperties properties) {
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.memberRepository = memberRepository;
        this.accessService = accessService;
        this.viewService = viewService;
        this.userClient = userClient;
        this.mediaClient = mediaClient;
        this.outboxService = outboxService;
        this.realtimePublisher = realtimePublisher;
        this.transactionTemplate = transactionTemplate;
        this.rules = properties.messages();
    }

    // ------------------------------------------------------------------ send

    public SendResult send(UUID senderId, SendMessageRequest request) {
        String content = checkTypeAndContent(request);

        // 1. a retry of something we already saved
        Optional<Message> existing = messageRepository.findBySenderIdAndIdempotencyKey(senderId, request.idempotencyKey());
        if (existing.isPresent()) {
            return new SendResult(viewService.toDto(existing.get(), senderId), false);
        }

        // 2 - 4. checks that call other services: done BEFORE the transaction so no database connection waits for them.
        // requireMember also refreshes our copy of the membership periods (its own small transaction).
        ConversationInfo conversation = accessService.requireMember(request.conversationId(), senderId);
        if (conversation.onlyAdminsMaySend() && !conversation.isAdmin(senderId)) {
            throw new ForbiddenException("Only admins can send messages to this group", "ONLY_ADMINS_CAN_SEND");
        }
        List<UUID> recipients = conversation.participantIds().stream().filter(id -> !id.equals(senderId)).toList();
        if (!conversation.isGroup()) {
            for (UUID recipient : recipients) {
                if (userClient.blockedEitherWay(senderId, recipient)) {
                    // The same answer whoever blocked whom, so a blocked user learns nothing extra.
                    throw new ForbiddenException("You can't message this user", "MESSAGING_NOT_ALLOWED");
                }
            }
        }

        // 5. media message: reserve the file (media-service checks that the sender uploaded it, that its kind matches
        //    and that no other message uses it). Same reference again = a retry, which is fine. Before the transaction.
        MediaClient.MediaInfo media = request.type() == MessageType.TEXT ? null
                : mediaClient.attach(request.attachmentId(), senderId, request.type().name(),
                        senderId + ":" + request.idempotencyKey());

        // 6. save + outbox in one transaction
        try {
            Message saved = transactionTemplate.execute(status -> persist(senderId, request, content, media, conversation, recipients));
            return new SendResult(viewService.toDto(saved, senderId), true);
        } catch (DataIntegrityViolationException e) {
            // Two requests with the same key arrived at the same moment; the other one won. Return its message.
            log.info("Concurrent send with the same idempotency key, returning the saved message");
            Message winner = messageRepository.findBySenderIdAndIdempotencyKey(senderId, request.idempotencyKey())
                    .orElseThrow(() -> e);
            return new SendResult(viewService.toDto(winner, senderId), false);
        }
    }

    /**
     * The rules that depend on the message type. Returns the text to store (for media: the caption, maybe empty).
     * TEXT: content required, no attachment. IMAGE / VIDEO / AUDIO / VOICE / DOCUMENT: attachment required, caption optional.
     * Everything else (SYSTEM is written by the server only, LOCATION and CONTACT are not built yet) is refused.
     */
    private String checkTypeAndContent(SendMessageRequest request) {
        MessageType type = request.type();
        if (type == MessageType.SYSTEM || type == MessageType.LOCATION || type == MessageType.CONTACT) {
            throw new BadRequestException("This message type cannot be sent", "UNSUPPORTED_TYPE");
        }
        String content = request.content() == null ? "" : request.content();
        if (content.length() > rules.maxLength()) {
            throw new BadRequestException("The text must be at most " + rules.maxLength() + " characters", "VALIDATION");
        }
        if (type == MessageType.TEXT) {
            if (request.attachmentId() != null) {
                throw new BadRequestException("A text message cannot have an attachment", "VALIDATION");
            }
            if (content.isBlank()) {
                throw new BadRequestException("The message must be 1 to " + rules.maxLength() + " characters", "VALIDATION");
            }
        } else if (request.attachmentId() == null) {
            throw new BadRequestException("attachmentId is required for this message type", "VALIDATION");
        }
        return content;
    }

    private Message persist(UUID senderId, SendMessageRequest request, String content, MediaClient.MediaInfo media,
                            ConversationInfo conversation, List<UUID> recipients) {
        Message message = new Message();
        message.setConversationId(conversation.id());
        message.setSenderId(senderId);
        message.setType(request.type());
        message.setContent(content);
        message.setIdempotencyKey(request.idempotencyKey());
        // The server decides the time (never the client), at the precision of the database column.
        message.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        messageRepository.saveAndFlush(message);   // flush: a duplicate key fails here, inside the transaction

        MessageAttachment attachment = null;
        if (media != null) {
            attachment = new MessageAttachment();
            attachment.setMessageId(message.getId());
            attachment.setMediaId(media.id());
            attachment.setKind(media.kind());
            attachment.setFileName(media.fileName());
            attachment.setMimeType(media.mimeType());
            attachment.setSizeBytes(media.sizeBytes());
            attachment.setWidth(media.width());
            attachment.setHeight(media.height());
            attachment.setDurationSeconds(media.durationSeconds());
            attachment.setHasThumbnail(Boolean.TRUE.equals(media.hasThumbnail()));
            attachmentRepository.save(attachment);
        }

        outboxService.queue("MessageCreatedEvent", conversation.id(), new MessageEvents.MessageCreated(
                message.getId(), conversation.id(), senderId, message.getType().name(), message.getCreatedAt(), recipients));

        // Live push to ALL current members, including the sender (their other devices, and a confirmation for this one).
        RealtimeEvent<MessageDto> event = RealtimeEvent.of("MESSAGE_CREATED", conversation.id(),
                viewService.toLiveDto(message, attachment));
        RealtimePublisher.afterCommit(() ->
                realtimePublisher.toUsers(conversation.participantIds(), "/queue/messages", event));
        return message;
    }

    // --------------------------------------------------------------- history

    /**
     * One page of a conversation.
     * - no cursor: the newest messages, newest first
     * - before=C: messages older than C, newest first
     * - after=C: messages newer than C, OLDEST first (used to catch up)
     */
    public PageDto<MessageDto> history(UUID userId, UUID conversationId, Integer limit, String before, String after) {
        int pageSize = pageSize(limit);
        if (before != null && after != null) {
            throw new BadRequestException("Use either 'before' or 'after', not both", "VALIDATION");
        }
        // current AND former members may read, but only the messages from their own membership periods
        accessService.requireReader(conversationId, userId);
        PageRequest page = PageRequest.of(0, pageSize + 1);

        if (after != null) {
            Cursor cursor = Cursor.decode(after);
            List<Message> rows = messageRepository.findNewerThan(conversationId, userId, cursor.createdAt(), cursor.id(), page);
            return newerPage(userId, rows, pageSize, cursor);
        }

        List<Message> rows = (before == null)
                ? messageRepository.findNewest(conversationId, userId, page)
                : decodeAndFindOlder(conversationId, userId, before, page);
        boolean hasMore = rows.size() > pageSize;
        List<Message> items = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore ? Cursor.of(items.get(items.size() - 1)).encode() : null;
        return new PageDto<>(viewService.toDtos(items, userId), nextCursor, hasMore);
    }

    private List<Message> decodeAndFindOlder(UUID conversationId, UUID userId, String before, PageRequest page) {
        Cursor cursor = Cursor.decode(before);
        return messageRepository.findOlderThan(conversationId, userId, cursor.createdAt(), cursor.id(), page);
    }

    // ------------------------------------------------------------------ sync

    /**
     * Everything new in ALL my conversations since a cursor, oldest first (including my own messages
     * sent from other devices, and SYSTEM messages). Only messages from my membership periods.
     * Without a cursor the answer is empty and nextCursor says where to start.
     */
    public PageDto<MessageDto> sync(UUID userId, String after, Integer limit) {
        int pageSize = pageSize(limit);
        if (after == null) {
            Instant start = Instant.now().minus(Duration.ofSeconds(rules.syncOverlapSeconds()));
            return new PageDto<>(List.of(), new Cursor(start, Cursor.ZERO_ID).encode(), false);
        }
        Cursor cursor = Cursor.decode(after);
        List<Message> rows = messageRepository.findForSync(userId, cursor.createdAt(), cursor.id(),
                PageRequest.of(0, pageSize + 1));
        return newerPage(userId, rows, pageSize, cursor);
    }

    /**
     * Builds an "oldest first" page. While more pages exist the cursor is exactly the last message, so paging
     * moves forward. When the client has caught up, the cursor is moved a few seconds BACK: a message whose
     * transaction committed slightly late is then still found next time (the client ignores duplicates by id).
     * The cursor never moves backwards compared with the one the client sent.
     */
    private PageDto<MessageDto> newerPage(UUID userId, List<Message> rows, int pageSize, Cursor incoming) {
        boolean hasMore = rows.size() > pageSize;
        List<Message> items = hasMore ? rows.subList(0, pageSize) : rows;
        Cursor next = incoming;
        if (!items.isEmpty()) {
            Message last = items.get(items.size() - 1);
            if (hasMore) {
                next = Cursor.of(last);
            } else {
                Instant lookBack = last.getCreatedAt().minusSeconds(rules.syncOverlapSeconds());
                next = lookBack.isAfter(incoming.createdAt()) ? new Cursor(lookBack, Cursor.ZERO_ID) : incoming;
            }
        }
        return new PageDto<>(viewService.toDtos(items, userId), next.encode(), hasMore);
    }

    // ------------------------------------------------------------- summaries

    /**
     * For the chat list: unread count and last message of every conversation in which I have messages I may see.
     * Groups I left are included (their last message is the last one from my membership time).
     * SYSTEM messages can be the last message but never count as unread.
     */
    public List<ConversationSummaryDto> summaries(UUID userId) {
        List<UUID> conversationIds = memberRepository
                .findConversationIdsOfUser(userId, PageRequest.of(0, MAX_CONVERSATIONS_IN_SUMMARY));
        if (conversationIds.isEmpty()) {
            return List.of();
        }

        Map<UUID, Long> unread = messageRepository.countUnread(conversationIds, userId).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));

        List<Message> lastMessages = new ArrayList<>();
        for (UUID conversationId : conversationIds) {
            lastMessages.addAll(messageRepository.findLatestVisible(conversationId, userId, PageRequest.of(0, 1)));
        }
        Map<UUID, MessageDto> lastById = viewService.toDtos(lastMessages, userId).stream()
                .collect(Collectors.toMap(MessageDto::conversationId, Function.identity()));

        return conversationIds.stream()
                .filter(lastById::containsKey)
                .map(id -> new ConversationSummaryDto(id, unread.getOrDefault(id, 0L), lastById.get(id)))
                .toList();
    }

    private int pageSize(Integer limit) {
        if (limit == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new BadRequestException("limit must be between 1 and " + MAX_PAGE_SIZE, "VALIDATION");
        }
        return limit;
    }
}
