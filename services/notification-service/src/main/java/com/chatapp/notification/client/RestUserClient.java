package com.chatapp.notification.client;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestUserClient implements UserClient {

    private static final Logger log = LoggerFactory.getLogger(RestUserClient.class);

    /** user-service's PublicProfile: we only read the display name. */
    private record Profile(UUID id, String displayName) {
    }

    private final RestClient restClient;

    public RestUserClient(InternalRestClientFactory factory, @Value("${chatapp.services.user-url}") String baseUrl) {
        this.restClient = factory.create(baseUrl);
    }

    @Override
    public Optional<String> displayName(UUID userId, UUID viewerId) {
        try {
            ApiEnvelope<List<Profile>> answer = restClient.post()
                    .uri("/internal/users/summaries")
                    .body(Map.of("viewerId", viewerId, "userIds", List.of(userId)))
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiEnvelope<List<Profile>>>() { });
            if (answer == null || answer.data() == null) {
                return Optional.empty();
            }
            return answer.data().stream()
                    .filter(profile -> userId.equals(profile.id()))
                    .map(Profile::displayName)
                    .filter(name -> name != null && !name.isBlank())
                    .findFirst();
        } catch (RuntimeException e) {
            log.warn("user-service name lookup failed ({})", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
