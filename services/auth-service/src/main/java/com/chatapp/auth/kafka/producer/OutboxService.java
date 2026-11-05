package com.chatapp.auth.kafka.producer;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.auth.config.CorrelationIdFilter;
import com.chatapp.auth.entity.OutboxEvent;
import com.chatapp.auth.kafka.EventEnvelope;
import com.chatapp.auth.repository.OutboxEventRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Queues an event in the outbox table. MANDATORY propagation means it must be called inside the
 * business transaction, so the event is saved (or rolled back) together with the business data.
 */
@Service
public class OutboxService {

    private final OutboxEventRepository repository;
    private final JsonMapper jsonMapper;

    public OutboxService(OutboxEventRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void queue(String eventType, UUID aggregateId, Object payload) {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();

        var envelope = new EventEnvelope<>(eventId, eventType, aggregateId, now, 1,
                MDC.get(CorrelationIdFilter.MDC_KEY), payload);

        OutboxEvent event = new OutboxEvent();
        event.setId(eventId);
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setPayload(jsonMapper.writeValueAsString(envelope));
        event.setCreatedAt(now);
        event.setPublished(false);
        repository.save(event);
    }
}
