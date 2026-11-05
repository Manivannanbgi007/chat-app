package com.chatapp.user.config;

import com.chatapp.user.kafka.consumer.InvalidEventException;
import com.chatapp.user.kafka.consumer.UserEventConsumer;
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
 * Kafka topics and error handling for the consumer.
 * A failing event is retried 3 times (after 1s, 5s, 25s). If it still fails it is copied to
 * "user.events.dlt" (dead-letter topic) so it does not block the other events.
 */
@Configuration
public class KafkaConfig {

    private static final String DEAD_LETTER_TOPIC = UserEventConsumer.TOPIC + ".dlt";

    @Bean
    public NewTopic userEventsTopic() {
        return TopicBuilder.name(UserEventConsumer.TOPIC).partitions(6).replicas(1).build();
    }

    @Bean
    public NewTopic userEventsDeadLetterTopic() {
        return TopicBuilder.name(DEAD_LETTER_TOPIC).partitions(6).replicas(1).build();
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // Same partition number as the original record, so the key order is kept.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(DEAD_LETTER_TOPIC, record.partition()));

        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 5.0);
        backOff.setMaxAttempts(3);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // Broken events can never succeed: skip the retries.
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }
}
