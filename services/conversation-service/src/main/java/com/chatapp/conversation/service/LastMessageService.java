package com.chatapp.conversation.service;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.conversation.entity.ProcessedEvent;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.conversation.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps "time of the last message" up to date (called by the Kafka consumer). */
@Service
public class LastMessageService {

    private static final Logger log = LoggerFactory.getLogger(LastMessageService.class);

    private final ProcessedEventRepository processedEventRepository;
    private final ConversationRepository conversationRepository;

    public LastMessageService(ProcessedEventRepository processedEventRepository,
                              ConversationRepository conversationRepository) {
        this.processedEventRepository = processedEventRepository;
        this.conversationRepository = conversationRepository;
    }

    /**
     * One transaction: either the event is marked as processed AND the time is updated, or nothing happened.
     * Handling the same event twice does nothing the second time, and an older message never moves the time back.
     */
    @Transactional
    public void messageCreated(UUID eventId, UUID conversationId, Instant createdAt) {
        if (processedEventRepository.existsById(eventId)) {
            log.info("Event {} was already processed, skipping", eventId);
            return;
        }
        processedEventRepository.save(new ProcessedEvent(eventId));

        int changed = conversationRepository.moveLastMessageAtForward(conversationId, createdAt);
        if (changed == 0 && !conversationRepository.existsById(conversationId)) {
            log.warn("MessageCreatedEvent for unknown conversation {}, ignoring", conversationId);
        }
    }
}
