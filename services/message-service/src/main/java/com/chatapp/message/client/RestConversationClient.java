package com.chatapp.message.client;

import java.util.Optional;
import java.util.UUID;

import com.chatapp.message.config.AppProperties;
import com.chatapp.message.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class RestConversationClient implements ConversationClient {

    private static final Logger log = LoggerFactory.getLogger(RestConversationClient.class);

    private final RestClient restClient;

    public RestConversationClient(InternalRestClientFactory factory, AppProperties properties) {
        this.restClient = factory.create(properties.services().conversationUrl());
    }

    @Override
    public Optional<ConversationInfo> find(UUID conversationId) {
        try {
            ApiEnvelope<ConversationInfo> answer = restClient.get()
                    .uri("/internal/conversations/{id}", conversationId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiEnvelope<ConversationInfo>>() { });
            return Optional.ofNullable(answer).map(ApiEnvelope::data);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            log.warn("conversation-service answered {} for a conversation lookup", e.getStatusCode().value());
            throw new ServiceUnavailableException("Conversation service is not available");
        } catch (RuntimeException e) {
            log.warn("conversation-service call failed ({})", e.getClass().getSimpleName());
            throw new ServiceUnavailableException("Conversation service is not available");
        }
    }
}
