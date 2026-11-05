package com.chatapp.notification.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.chatapp.notification.client.ConversationClient;
import com.chatapp.notification.client.ConversationClient.ConversationInfo;
import com.chatapp.notification.client.PresenceClient;
import com.chatapp.notification.entity.DeliveryLog;
import com.chatapp.notification.entity.DeviceToken;
import com.chatapp.notification.entity.Notification;
import com.chatapp.notification.entity.NotificationPreference;
import com.chatapp.notification.enums.DeliveryStatus;
import com.chatapp.notification.enums.NotificationType;
import com.chatapp.notification.mapper.NotificationMapper;
import com.chatapp.notification.realtime.LiveNotificationPublisher;
import com.chatapp.notification.repository.DeliveryLogRepository;
import com.chatapp.notification.repository.DeviceTokenRepository;
import com.chatapp.notification.repository.NotificationRepository;
import com.chatapp.notification.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns Kafka events into notifications. One method per kind of event, all with the same shape:
 *
 *   1. Already handled this event id?  Then do nothing (Kafka may deliver an event twice).
 *   2. Ask the other services what we need (presence: who is online, conversation: chat type, name, mutes,
 *      user: sender name). This is done BEFORE the database transaction, so no connection waits for the network.
 *   3. ONE database transaction: remember the event id + create / update the notifications + queue the pushes.
 *      If anything fails the whole event is rolled back and Kafka retries it (then the dead-letter topic).
 *   4. After the commit: tell the browsers live (Redis). A Redis problem never fails the event.
 *
 * Message text never reaches this service (the events do not carry it).
 */
@Service
public class NotificationEventService {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventService.class);
    private static final int MAX_LOCK_ATTEMPTS = 4;

    private final NotificationRepository notificationRepository;
    private final DeliveryLogRepository deliveryLogRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final PreferenceService preferenceService;
    private final ConversationClient conversationClient;
    private final PresenceClient presenceClient;
    private final NameLookup nameLookup;
    private final LiveNotificationPublisher livePublisher;
    private final NotificationMapper mapper;
    private final TransactionTemplate transactionTemplate;

    public NotificationEventService(NotificationRepository notificationRepository,
                                    DeliveryLogRepository deliveryLogRepository,
                                    DeviceTokenRepository deviceTokenRepository,
                                    ProcessedEventRepository processedEventRepository,
                                    PreferenceService preferenceService, ConversationClient conversationClient,
                                    PresenceClient presenceClient, NameLookup nameLookup,
                                    LiveNotificationPublisher livePublisher, NotificationMapper mapper,
                                    TransactionTemplate transactionTemplate) {
        this.notificationRepository = notificationRepository;
        this.deliveryLogRepository = deliveryLogRepository;
        this.deviceTokenRepository = deviceTokenRepository;
        this.processedEventRepository = processedEventRepository;
        this.preferenceService = preferenceService;
        this.conversationClient = conversationClient;
        this.presenceClient = presenceClient;
        this.nameLookup = nameLookup;
        this.livePublisher = livePublisher;
        this.mapper = mapper;
        this.transactionTemplate = transactionTemplate;
    }

    // ----------------------------------------------------------- new messages

    /**
     * A message was sent (type is not SYSTEM). Recipients who are offline, did not mute the chat and want these
     * notifications get / grow ONE "N new messages" notification for that chat.
     */
    public void onMessageCreated(UUID eventId, UUID conversationId, UUID senderId, Collection<UUID> recipientIds) {
        Set<UUID> recipients = new LinkedHashSet<>(recipientIds);
        recipients.remove(senderId);
        if (recipients.isEmpty() || alreadyHandled(eventId)) {
            return;
        }
        Optional<ConversationInfo> conversation = conversationClient.find(conversationId);
        if (conversation.isEmpty()) {
            log.warn("Message event {} is for an unknown conversation, ignoring it", eventId);
            return;
        }
        boolean group = conversation.get().isGroup();
        Set<UUID> muted = conversationClient.mutedUserIds(conversationId);
        Set<UUID> online = presenceClient.online(recipients);
        String senderName = group ? null : nameLookup.displayName(senderId, recipients.iterator().next());
        String title = NotificationRules.messageTitle(group, senderName, conversation.get().name());

        List<Notification> changed = inTransaction(eventId, () -> {
            Map<UUID, NotificationPreference> preferences = preferenceService.loadAll(recipients);
            Set<UUID> usersWithTokens = usersWithTokens(recipients);
            List<Notification> result = new ArrayList<>();
            Instant now = now();
            for (UUID recipient : recipients) {
                NotificationPreference preference = preferences.get(recipient);
                if (muted.contains(recipient) || online.contains(recipient)
                        || !NotificationRules.wantsMessageNotification(preference, group)) {
                    continue;
                }
                String key = NotificationRepository.newMessagesKey(recipient, conversationId);
                notificationRepository.upsertNewMessages(UUID.randomUUID().toString(), recipient.toString(),
                        NotificationType.NEW_MESSAGES.name(), title, conversationId.toString(), key, now);
                Notification notification = notificationRepository.findByUnreadKey(key).orElseThrow();
                result.add(notification);
                if (NotificationRules.wantsPush(preference, usersWithTokens.contains(recipient), false)) {
                    queuePush(notification);
                }
            }
            return result;
        });
        publishLive(changed);
    }

    /** A recipient read a message: their unread "new messages" notification of that chat is done. */
    public void onMessageRead(UUID eventId, UUID recipientId, UUID conversationId) {
        if (alreadyHandled(eventId)) {
            return;
        }
        List<Notification> changed = inTransaction(eventId, () ->
                notificationRepository.findUnreadNewMessages(recipientId, conversationId)
                        .map(notification -> {
                            notification.markRead(now());
                            return List.of(notification);
                        })
                        .orElse(List.of()));
        publishLive(changed);
    }

    // ------------------------------------------------------------ group changes

    /** A group was created: everybody except the creator was added. */
    public void onGroupCreated(UUID eventId, UUID conversationId, UUID createdBy, String createdByName,
                               String groupName, Collection<UUID> participantIds) {
        groupNotifications(eventId, conversationId, NotificationType.GROUP_ADDED, groupName,
                NotificationRules.addedBody(createdByName), without(participantIds, createdBy));
    }

    /** Members were added to a group (the person who added them gets nothing). */
    public void onMembersAdded(UUID eventId, UUID conversationId, UUID actorId, String actorName,
                               Collection<UUID> addedUserIds) {
        groupNotifications(eventId, conversationId, NotificationType.GROUP_ADDED, null,
                NotificationRules.addedBody(actorName), without(addedUserIds, actorId));
    }

    /** A member was removed from a group (leaving on your own does not notify anybody). */
    public void onMemberRemoved(UUID eventId, UUID conversationId, String actorName, UUID removedUserId) {
        groupNotifications(eventId, conversationId, NotificationType.GROUP_REMOVED, null,
                NotificationRules.removedBody(actorName), List.of(removedUserId));
    }

    /** Somebody became admin. */
    public void onMadeAdmin(UUID eventId, UUID conversationId, UUID userId) {
        groupNotifications(eventId, conversationId, NotificationType.GROUP_ADMIN, null,
                NotificationRules.adminBody(), List.of(userId));
    }

    /**
     * The shared part of all group notifications. They are created whether the user is online or not (they change
     * what the user can do), as long as the user wants group notifications. Each one is a NEW row, never aggregated.
     */
    private void groupNotifications(UUID eventId, UUID conversationId, NotificationType type, String groupNameFromEvent,
                                    String body, Collection<UUID> recipientIds) {
        Set<UUID> recipients = new LinkedHashSet<>(recipientIds);
        if (recipients.isEmpty() || alreadyHandled(eventId)) {
            return;
        }
        String groupName = groupNameFromEvent;
        if (groupName == null || groupName.isBlank()) {
            groupName = conversationClient.find(conversationId).map(ConversationInfo::name).orElse(null);
        }
        String title = NotificationRules.groupTitle(groupName);
        Set<UUID> online = presenceClient.online(recipients);

        List<Notification> changed = inTransaction(eventId, () -> {
            Map<UUID, NotificationPreference> preferences = preferenceService.loadAll(recipients);
            Set<UUID> usersWithTokens = usersWithTokens(recipients);
            List<Notification> result = new ArrayList<>();
            Instant now = now();
            for (UUID recipient : recipients) {
                NotificationPreference preference = preferences.get(recipient);
                if (!NotificationRules.wantsGroupNotification(preference)) {
                    continue;
                }
                Notification notification = new Notification();
                notification.setUserId(recipient);
                notification.setType(type);
                notification.setTitle(title);
                notification.setBody(body);
                notification.setConversationId(conversationId);
                notification.setCount(1);
                notification.setRead(false);
                // unique per event, so two group events never collide; cleared when the notification is read
                notification.setUnreadKey(recipient + ":" + type + ":" + eventId);
                notification.setCreatedAt(now);
                notification.setUpdatedAt(now);
                notificationRepository.save(notification);
                result.add(notification);
                if (NotificationRules.wantsPush(preference, usersWithTokens.contains(recipient), online.contains(recipient))) {
                    queuePush(notification);
                }
            }
            return result;
        });
        publishLive(changed);
    }

    // ------------------------------------------------------------------- calls

    /**
     * A call to calleeId ended without being taken (MISSED or BUSY, Phase 7). The callee gets ONE new "Missed voice
     * call" / "Missed video call" notification (never merged with earlier ones), whether they are online or not, if they
     * want call notifications. A push is queued only when they are offline (the same rule as every notification).
     */
    public void onMissedCall(UUID eventId, UUID callerId, UUID calleeId, boolean video) {
        if (callerId.equals(calleeId) || alreadyHandled(eventId)) {
            return;
        }
        String callerName = nameLookup.displayName(callerId, calleeId);     // "Someone" when unknown
        boolean online = presenceClient.online(Set.of(calleeId)).contains(calleeId);

        List<Notification> changed = inTransaction(eventId, () -> {
            NotificationPreference preference = preferenceService.loadAll(List.of(calleeId)).get(calleeId);
            if (!NotificationRules.wantsCallNotification(preference)) {
                return List.of();
            }
            Instant now = now();
            Notification notification = new Notification();
            notification.setUserId(calleeId);
            notification.setType(NotificationType.MISSED_CALL);
            notification.setTitle(NotificationRules.nameOrSomeone(callerName));
            notification.setBody(NotificationRules.missedCallBody(video));
            notification.setConversationId(null);
            notification.setCount(1);
            notification.setRead(false);
            // unique per event (like group notifications), so two missed calls never merge; cleared when read
            notification.setUnreadKey(calleeId + ":" + NotificationType.MISSED_CALL + ":" + eventId);
            notification.setCreatedAt(now);
            notification.setUpdatedAt(now);
            notificationRepository.save(notification);
            if (NotificationRules.wantsPush(preference, !usersWithTokens(List.of(calleeId)).isEmpty(), online)) {
                queuePush(notification);
            }
            return List.of(notification);
        });
        publishLive(changed);
    }

    // ------------------------------------------------------------------ helpers

    private boolean alreadyHandled(UUID eventId) {
        return processedEventRepository.existsById(eventId);
    }

    /**
     * Runs the work in one transaction together with the "event handled" record. Returns the notifications that
     * changed (empty for a duplicate event). If two events of the same chat meet in the database at the same moment
     * the database may abort one of them with a lock error; the work is simply tried again.
     */
    private List<Notification> inTransaction(UUID eventId, Supplier<List<Notification>> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                List<Notification> result = transactionTemplate.execute(status -> {
                    if (processedEventRepository.insertIfNew(eventId.toString(), now()) == 0) {
                        return List.<Notification>of();       // another instance handled it in the meantime
                    }
                    return work.get();
                });
                return result == null ? List.of() : result;
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= MAX_LOCK_ATTEMPTS) {
                    throw e;
                }
                log.debug("Lock collision while handling event {}, trying again (attempt {})", eventId, attempt);
                pause(attempt);
            }
        }
    }

    /** A push is queued in the same transaction as the notification, so it can never be lost. */
    private void queuePush(Notification notification) {
        // "3 new messages" is ONE push: if the previous push of this notification was not sent yet, it will carry the new text
        if (deliveryLogRepository.existsByNotificationIdAndStatusIn(notification.getId(),
                List.of(DeliveryStatus.PENDING, DeliveryStatus.FAILED))) {
            return;
        }
        Instant now = now();
        DeliveryLog delivery = new DeliveryLog();
        delivery.setNotificationId(notification.getId());
        delivery.setUserId(notification.getUserId());
        delivery.setChannel(DeliveryLog.CHANNEL_PUSH);
        delivery.setStatus(DeliveryStatus.PENDING);
        delivery.setAttempts(0);
        delivery.setNextAttemptAt(now);
        delivery.setCreatedAt(now);
        delivery.setUpdatedAt(now);
        deliveryLogRepository.save(delivery);
    }

    private Set<UUID> usersWithTokens(Collection<UUID> userIds) {
        return deviceTokenRepository.findByUserIdIn(userIds).stream()
                .map(DeviceToken::getUserId)
                .collect(Collectors.toSet());
    }

    private void publishLive(List<Notification> changed) {
        for (Notification notification : changed) {
            livePublisher.publish(notification.getUserId(), mapper.toResponse(notification));
        }
    }

    private static List<UUID> without(Collection<UUID> ids, UUID excluded) {
        return ids.stream().filter(id -> !id.equals(excluded)).toList();
    }

    /** The database stores microseconds. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static void pause(int attempt) {
        try {
            Thread.sleep(20L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
