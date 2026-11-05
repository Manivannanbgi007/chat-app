package com.chatapp.call.kafka.producer;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.call.config.CorrelationIdFilter;
import com.chatapp.call.entity.OutboxEvent;
import com.chatapp.call.repository.OutboxEventRepository;
import com.chatapp.call.util.Times;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Queues an event in the outbox table. MANDATORY propagation means it must be called inside the
 * business transaction, so the event is saved (or rolled back) together with the business data.
 *
 * The publisher sends rows in created_at order, so created_at must never repeat: each event gets a time at least
 * one microsecond after the previous one.
 */
@Service
public class OutboxService {

    private final OutboxEventRepository repository;
    private final JsonMapper jsonMapper;
    private Instant lastQueuedAt = Instant.EPOCH;

    public OutboxService(OutboxEventRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void queue(String eventType, UUID aggregateId, Object payload) {
        UUID eventId = UUID.randomUUID();
        Instant now = nextTime();

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

    /** "Now" in microseconds, but always later than the previous call (keeps the order of events). */
    private synchronized Instant nextTime() {
        Instant now = Times.now();
        if (!now.isAfter(lastQueuedAt)) {
            now = lastQueuedAt.plusNanos(1_000);
        }
        lastQueuedAt = now;
        return now;
    }
}
