package com.chatapp.message.kafka.producer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.chatapp.message.config.AppConfig;
import com.chatapp.message.entity.OutboxEvent;
import com.chatapp.message.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every half second: send unpublished outbox rows to Kafka, oldest first, then mark them published.
 * If Kafka is down the rows simply wait; nothing is lost and sending messages keeps working.
 * Delivery is "at least once": a crash between send and mark can send an event twice,
 * which is why every consumer ignores events it has already processed.
 * The Kafka key is the conversation id, so all events of one conversation stay in order.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    /** Which topic each event type goes to. */
    private static final Map<String, String> TOPIC_BY_EVENT_TYPE = Map.of(
            "MessageCreatedEvent", AppConfig.MESSAGE_EVENTS_TOPIC,
            "MessageDeliveredEvent", AppConfig.MESSAGE_EVENTS_TOPIC,
            "MessageReadEvent", AppConfig.MESSAGE_EVENTS_TOPIC);

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxPublisher(OutboxEventRepository repository, KafkaTemplate<String, String> kafkaTemplate) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${chatapp.outbox.poll-ms:500}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = repository.lockNextBatch();
        for (OutboxEvent event : batch) {
            String topic = TOPIC_BY_EVENT_TYPE.get(event.getEventType());
            try {
                if (topic == null) {
                    throw new IllegalStateException("No topic configured for " + event.getEventType());
                }
                kafkaTemplate.send(topic, event.getAggregateId().toString(), event.getPayload())
                        .get(5, TimeUnit.SECONDS);
                event.setPublished(true);
                event.setPublishedAt(Instant.now());
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // Stop here to keep the order; the same row is tried again on the next run.
                log.warn("Could not publish outbox event {} ({}), will retry", event.getId(), e.getClass().getSimpleName());
                return;
            }
        }
    }

    /** Published rows are only needed for a short time. */
    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public void deleteOldPublishedEvents() {
        int deleted = repository.deletePublishedBefore(Instant.now().minus(1, ChronoUnit.DAYS));
        if (deleted > 0) {
            log.info("Deleted {} old outbox rows", deleted);
        }
    }
}
