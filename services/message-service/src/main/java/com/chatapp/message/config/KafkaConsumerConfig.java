package com.chatapp.message.config;

import com.chatapp.message.kafka.consumer.InvalidEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * The topic we READ (conversation.events, written by conversation-service) and what happens when an event fails.
 * A failing event is retried 3 times (after 1 s, 5 s, 25 s). If it still fails it is copied to
 * "conversation.events.dlt" (dead-letter topic) so it does not block the events behind it.
 * Broken events (InvalidEventException) can never succeed and go to the dead-letter topic at once.
 */
@Configuration
public class KafkaConsumerConfig {

    public static final String CONVERSATION_EVENTS_TOPIC = "conversation.events";
    public static final String CONVERSATION_EVENTS_DLT = CONVERSATION_EVENTS_TOPIC + ".dlt";

    /** conversation-service creates it too; creating it here as well avoids start-up order problems. */
    @Bean
    public NewTopic conversationEventsTopic() {
        return TopicBuilder.name(CONVERSATION_EVENTS_TOPIC).partitions(6).replicas(1).build();
    }

    @Bean
    public NewTopic conversationEventsDeadLetterTopic() {
        return TopicBuilder.name(CONVERSATION_EVENTS_DLT).partitions(6).replicas(1).build();
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // Same partition number as the original record, so the order per conversation is kept.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(CONVERSATION_EVENTS_DLT, record.partition()));

        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 5.0);
        backOff.setMaxAttempts(3);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }
}
