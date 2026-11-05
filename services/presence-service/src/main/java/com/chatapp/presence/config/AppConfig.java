package com.chatapp.presence.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PresenceProperties.class)
public class AppConfig {

    /** The one source of "now" (a bean so tests can replace it). */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
