package com.chatapp.message.client;

import java.util.UUID;

import com.chatapp.message.config.AppProperties;
import com.chatapp.message.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestUserClient implements UserClient {

    private static final Logger log = LoggerFactory.getLogger(RestUserClient.class);

    private final RestClient restClient;

    public RestUserClient(InternalRestClientFactory factory, AppProperties properties) {
        this.restClient = factory.create(properties.services().userUrl());
    }

    private record BlockAnswer(boolean blocked) {
    }

    private record PrivacyAnswer(Boolean readReceipts) {
    }

    @Override
    public boolean blockedEitherWay(UUID userA, UUID userB) {
        try {
            ApiEnvelope<BlockAnswer> answer = restClient.get()
                    .uri("/internal/blocks?a={a}&b={b}", userA, userB)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiEnvelope<BlockAnswer>>() { });
            if (answer == null || answer.data() == null) {
                throw new ServiceUnavailableException("User service is not available");
            }
            return answer.data().blocked();
        } catch (ServiceUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("user-service block check failed ({})", e.getClass().getSimpleName());
            throw new ServiceUnavailableException("User service is not available");
        }
    }

    @Override
    public boolean readReceiptsEnabled(UUID userId) {
        try {
            ApiEnvelope<PrivacyAnswer> answer = restClient.get()
                    .uri("/internal/users/{id}/privacy", userId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiEnvelope<PrivacyAnswer>>() { });
            // A missing value means "default", and the default is: read receipts on.
            return answer == null || answer.data() == null || !Boolean.FALSE.equals(answer.data().readReceipts());
        } catch (RuntimeException e) {
            log.warn("user-service privacy lookup failed ({}), hiding read receipts", e.getClass().getSimpleName());
            return false;
        }
    }
}
