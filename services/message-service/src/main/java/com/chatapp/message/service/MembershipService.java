package com.chatapp.message.service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chatapp.message.client.ConversationClient.ConversationInfo;
import com.chatapp.message.client.ConversationClient.MemberPeriod;
import com.chatapp.message.entity.ConversationMember;
import com.chatapp.message.repository.ConversationMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps our copy of the membership periods in line with conversation-service's answer.
 * Called every time we have just asked conversation-service about a conversation.
 *
 * The copy only ever moves FORWARD:
 *  - a period we do not know yet is added (with its end time if it already ended)
 *  - an open period that the answer shows as ended is closed
 *  - a closed period is never re-opened and nothing is deleted
 * Why: Kafka events (ConversationEventConsumer) update the same rows. An answer that is a few milliseconds old must
 * not undo a removal that an event has already recorded.
 *
 * It runs in its OWN short transaction, separate from saving a message, because two messages sent at the same moment
 * in a brand new conversation both want to add the same periods. If they collide the database may abort one of them;
 * we simply try again (the work is idempotent). Most calls find nothing to change and never write at all.
 */
@Service
public class MembershipService {

    private static final Logger log = LoggerFactory.getLogger(MembershipService.class);
    private static final int MAX_ATTEMPTS = 4;

    private final ConversationMemberRepository repository;
    private final TransactionTemplate transactionTemplate;

    public MembershipService(ConversationMemberRepository repository, TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
    }

    public void sync(ConversationInfo conversation) {
        if (conversation.members().isEmpty() || isUpToDate(conversation)) {
            return;
        }
        for (int attempt = 1; ; attempt++) {
            try {
                transactionTemplate.executeWithoutResult(status -> apply(conversation));
                return;
            } catch (CannotAcquireLockException | DataIntegrityViolationException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.debug("Membership sync collided with another request, retrying (attempt {})", attempt);
                pause(attempt);
            }
        }
    }

    /** True when applying the answer would change nothing (every period is known, and closed where it must be). */
    private boolean isUpToDate(ConversationInfo conversation) {
        Map<String, ConversationMember> known = byPeriodKey(repository.findByConversationId(conversation.id()));
        for (MemberPeriod period : conversation.members()) {
            ConversationMember local = known.get(key(period.userId(), period));
            if (local == null || (period.leftAt() != null && local.isOpen())) {
                return false;
            }
        }
        return true;
    }

    private void apply(ConversationInfo conversation) {
        Map<String, ConversationMember> known = byPeriodKey(repository.findByConversationId(conversation.id()));
        // always in the same order, so two requests lock the rows in the same order
        List<MemberPeriod> periods = conversation.members().stream()
                .sorted(Comparator.comparing(MemberPeriod::userId).thenComparing(MemberPeriod::joinedAt))
                .toList();
        for (MemberPeriod period : periods) {
            ConversationMember local = known.get(key(period.userId(), period));
            if (local == null) {
                repository.insertIgnore(UUID.randomUUID().toString(), conversation.id().toString(),
                        period.userId().toString(), conversation.typeOrPrivate(), period.joinedAt(), period.leftAt());
            } else if (local.isOpen() && period.leftAt() != null) {
                local.setLeftAt(period.leftAt());       // the member left or was removed
            }
        }
    }

    private static Map<String, ConversationMember> byPeriodKey(List<ConversationMember> rows) {
        Map<String, ConversationMember> byKey = new HashMap<>();
        rows.forEach(row -> byKey.put(row.getUserId() + "|" + row.getJoinedAt(), row));
        return byKey;
    }

    private static String key(UUID userId, MemberPeriod period) {
        return userId + "|" + period.joinedAt();
    }

    private void pause(int attempt) {
        try {
            Thread.sleep(20L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
