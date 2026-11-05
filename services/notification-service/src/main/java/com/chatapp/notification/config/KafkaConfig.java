package com.chatapp.notification.config;

import com.chatapp.notification.kafka.InvalidEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka topics and error handling.
 * A failing event is retried 3 times (after 1s, 5s, 25s). If it still fails it is copied to "<topic>.dlt"
 * (the dead-letter topic of that topic) so it does not block the other events.
 */
@Configuration
@EnableScheduling
public class KafkaConfig {

    public static final String MESSAGE_EVENTS_TOPIC = "message.events";
    public static final String CONVERSATION_EVENTS_TOPIC = "conversation.events";
    /** Phase 7: written by call-signaling-service (CallEndedEvent). */
    public static final String CALL_EVENTS_TOPIC = "call.events";

    // We only read these topics (message-, conversation- and call-signaling-service write them). Declaring them here is
    // harmless and avoids start-up order problems. The dead-letter topics are written by us.
    @Bean
    public NewTopic messageEventsTopic() {
        return TopicBuilder.name(MESSAGE_EVENTS_TOPIC).partitions(6).replicas(1).build();
    }

    @Bean
    public NewTopic conversationEventsTopic() {
        return TopicBuilder.name(CONVERSATION_EVENTS_TOPIC).partitions(6).replicas(1).build();
    }

    @Bean
    public NewTopic callEventsTopic() {
        return TopicBuilder.name(CALL_EVENTS_TOPIC).partitions(6).replicas(1).build();
    }

    @Bean
    public NewTopic callEventsDeadLetterTopic() {
        return TopicBuilder.name(CALL_EVENTS_TOPIC + ".dlt").partitions(6).replicas(1).build();
    }

    @Bean
    public NewTopic messageEventsDeadLetterTopic() {
        return TopicBuilder.name(MESSAGE_EVENTS_TOPIC + ".dlt").partitions(6).replicas(1).build();
    }

    @Bean
    public NewTopic conversationEventsDeadLetterTopic() {
        return TopicBuilder.name(CONVERSATION_EVENTS_TOPIC + ".dlt").partitions(6).replicas(1).build();
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                 @Value("${chatapp.kafka.retry-initial-ms:1000}") long initialDelayMs,
                                                 @Value("${chatapp.kafka.retry-multiplier:5.0}") double multiplier) {
        // Same partition number as the original record, so the key order is kept.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + ".dlt", record.partition()));

        ExponentialBackOff backOff = new ExponentialBackOff(initialDelayMs, multiplier);   // 1 s, 5 s, 25 s by default
        backOff.setMaxAttempts(3);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // Broken events can never succeed: skip the retries.
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }
}
