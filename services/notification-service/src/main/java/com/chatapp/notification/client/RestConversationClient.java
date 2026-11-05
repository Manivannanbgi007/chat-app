package com.chatapp.notification.client;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.chatapp.notification.exception.DependencyUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class RestConversationClient implements ConversationClient {

    private static final Logger log = LoggerFactory.getLogger(RestConversationClient.class);

    private record MutedAnswer(List<UUID> mutedUserIds) {
    }

    private final RestClient restClient;

    public RestConversationClient(InternalRestClientFactory factory,
                                  @Value("${chatapp.services.conversation-url}") String baseUrl) {
        this.restClient = factory.create(baseUrl);
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
            throw unavailable(e);
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
    }

    @Override
    public Set<UUID> mutedUserIds(UUID conversationId) {
        try {
            ApiEnvelope<MutedAnswer> answer = restClient.get()
                    .uri("/internal/conversations/{id}/muted-users", conversationId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiEnvelope<MutedAnswer>>() { });
            if (answer == null || answer.data() == null || answer.data().mutedUserIds() == null) {
                return Set.of();
            }
            return new HashSet<>(answer.data().mutedUserIds());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Set.of();
            }
            throw unavailable(e);
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
    }

    /** One consistent exception type for "conversation-service did not help": Kafka retries, then the dead-letter topic. */
    private DependencyUnavailableException unavailable(Exception e) {
        log.warn("conversation-service call failed ({})", e.getClass().getSimpleName());
        return new DependencyUnavailableException("Conversation service is not available");
    }
}
