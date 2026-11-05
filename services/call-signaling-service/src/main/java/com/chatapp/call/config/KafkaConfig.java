package com.chatapp.call.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** The Kafka topic this service writes (it reads none). Key = call id. */
@Configuration
public class KafkaConfig {

    public static final String CALL_EVENTS_TOPIC = "call.events";

    @Bean
    public NewTopic callEventsTopic() {
        return TopicBuilder.name(CALL_EVENTS_TOPIC).partitions(6).replicas(1).build();
    }
}
