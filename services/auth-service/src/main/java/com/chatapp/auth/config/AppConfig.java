package com.chatapp.auth.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(AuthProperties.class)
public class AppConfig {

    public static final String USER_EVENTS_TOPIC = "user.events";

    /** Creates the topic on startup if it does not exist yet (6 partitions is plenty for local use). */
    @Bean
    public NewTopic userEventsTopic() {
        return TopicBuilder.name(USER_EVENTS_TOPIC).partitions(6).replicas(1).build();
    }
}
