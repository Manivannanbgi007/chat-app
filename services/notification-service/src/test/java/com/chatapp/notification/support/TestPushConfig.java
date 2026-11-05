package com.chatapp.notification.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestPushConfig {

    @Bean
    @Primary
    public TestPushSender testPushSender() {
        return new TestPushSender();
    }
}
