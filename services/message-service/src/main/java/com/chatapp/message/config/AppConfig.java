package com.chatapp.message.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
public class AppConfig {

    public static final String MESSAGE_EVENTS_TOPIC = "message.events";

    /** Creates the topic on startup if it does not exist yet (6 partitions is plenty for local use). */
    @Bean
    public NewTopic messageEventsTopic() {
        return TopicBuilder.name(MESSAGE_EVENTS_TOPIC).partitions(6).replicas(1).build();
    }
}
