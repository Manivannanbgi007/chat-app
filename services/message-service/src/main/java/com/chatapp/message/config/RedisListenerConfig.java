package com.chatapp.message.config;

import com.chatapp.message.websocket.RealtimePublisher;
import com.chatapp.message.websocket.RealtimeSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** Subscribes this instance to the Redis channel that carries live events for all instances. */
@Configuration
public class RedisListenerConfig {

    @Bean
    public RedisMessageListenerContainer realtimeListenerContainer(RedisConnectionFactory connectionFactory,
                                                                   RealtimeSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(RealtimePublisher.REDIS_CHANNEL));
        return container;
    }
}
