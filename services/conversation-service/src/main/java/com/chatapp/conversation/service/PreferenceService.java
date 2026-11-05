package com.chatapp.conversation.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.chatapp.conversation.dto.response.ConversationResponse;
import com.chatapp.conversation.entity.ConversationParticipant;
import com.chatapp.conversation.enums.MuteDuration;
import com.chatapp.conversation.exception.ConflictException;
import com.chatapp.conversation.exception.ResourceNotFoundException;
import com.chatapp.conversation.repository.ParticipantRepository;
import com.chatapp.conversation.util.Times;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * My own settings for a chat: mute, archive, pin. They only change what I see (nobody else is told).
 * They work for every chat I am or was in (also a group I left). Doing the same thing twice changes nothing.
 *
 *  - archiving a chat also unpins it, and pinning a chat also takes it out of the archive (an archived chat
 *    is hidden in the "Archived" folder, so it cannot also sit at the top of the list)
 *  - at most 3 chats can be pinned (409 PIN_LIMIT_REACHED)
 */
@Service
public class PreferenceService {

    public static final int MAX_PINNED = 3;

    private final ParticipantRepository participantRepository;
    private final ConversationViewService viewService;
    private final TransactionTemplate transactionTemplate;

    public PreferenceService(ParticipantRepository participantRepository, ConversationViewService viewService,
                             PlatformTransactionManager transactionManager) {
        this.participantRepository = participantRepository;
        this.viewService = viewService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ConversationResponse mute(UUID me, UUID conversationId, MuteDuration duration) {
        return change(me, conversationId, row -> row.setMutedUntil(duration.until(Times.now())));
    }

    public ConversationResponse unmute(UUID me, UUID conversationId) {
        return change(me, conversationId, row -> row.setMutedUntil(null));
    }

    public ConversationResponse archive(UUID me, UUID conversationId) {
        return change(me, conversationId, row -> {
            if (row.getArchivedAt() == null) {
                row.setArchivedAt(Times.now());
            }
            row.setPinnedAt(null);
        });
    }

    public ConversationResponse unarchive(UUID me, UUID conversationId) {
        return change(me, conversationId, row -> row.setArchivedAt(null));
    }

    public ConversationResponse pin(UUID me, UUID conversationId) {
        transactionTemplate.executeWithoutResult(status -> {
            // Lock all my rows: two "pin" requests at the same moment run one after the other,
            // so the second one sees the first one's pin and the limit holds.
            List<ConversationParticipant> myRows = participantRepository.findByUserIdForUpdate(me);
            ConversationParticipant row = myRows.stream()
                    .filter(p -> p.getConversationId().equals(conversationId))
                    .findFirst()
                    .orElseThrow(PreferenceService::notFound);
            if (row.getPinnedAt() != null) {
                return;                             // already pinned
            }
            long pinned = myRows.stream().filter(p -> p.getPinnedAt() != null).count();
            if (pinned >= MAX_PINNED) {
                throw new ConflictException("You can pin up to " + MAX_PINNED + " chats", "PIN_LIMIT_REACHED");
            }
            row.setPinnedAt(Times.now());
            row.setArchivedAt(null);
            participantRepository.save(row);
        });
        return viewService.viewOne(me, conversationId, Map.of());
    }

    public ConversationResponse unpin(UUID me, UUID conversationId) {
        return change(me, conversationId, row -> row.setPinnedAt(null));
    }

    /** Loads my row (404 if I was never in the chat), applies the change, saves, and returns the chat. */
    private ConversationResponse change(UUID me, UUID conversationId, Consumer<ConversationParticipant> change) {
        transactionTemplate.executeWithoutResult(status -> {
            ConversationParticipant row = participantRepository.findByConversationIdAndUserId(conversationId, me)
                    .orElseThrow(PreferenceService::notFound);
            change.accept(row);
            participantRepository.save(row);
        });
        return viewService.viewOne(me, conversationId, Map.of());
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Conversation not found");
    }
}
