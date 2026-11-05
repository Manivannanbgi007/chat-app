package com.chatapp.conversation.config;

import com.chatapp.conversation.kafka.consumer.InvalidEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
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
 * A failing event is retried 3 times (after 1s, 5s, 25s). If it still fails it is copied to
 * "message.events.dlt" (dead-letter topic) so it does not block the other events.
 */
@Configuration
@EnableScheduling
public class KafkaConfig {

    public static final String CONVERSATION_EVENTS_TOPIC = "conversation.events";
    public static final String MESSAGE_EVENTS_TOPIC = "message.events";
    public static final String MESSAGE_EVENTS_DLT = MESSAGE_EVENTS_TOPIC + ".dlt";

    @Bean
    public NewTopic conversationEventsTopic() {
        return TopicBuilder.name(CONVERSATION_EVENTS_TOPIC).partitions(6).replicas(1).build();
    }

    /** We read this topic, but message-service writes it: creating it here too is harmless and avoids start-up order problems. */
    @Bean
    public NewTopic messageEventsTopic() {
        return TopicBuilder.name(MESSAGE_EVENTS_TOPIC).partitions(6).replicas(1).build();
    }

    @Bean
    public NewTopic messageEventsDeadLetterTopic() {
        return TopicBuilder.name(MESSAGE_EVENTS_DLT).partitions(6).replicas(1).build();
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // Same partition number as the original record, so the key order is kept.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(MESSAGE_EVENTS_DLT, record.partition()));

        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 5.0);
        backOff.setMaxAttempts(3);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // Broken events can never succeed: skip the retries.
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }
}
