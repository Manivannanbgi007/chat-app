package com.chatapp.presence.client;

import java.util.Map;
import java.util.UUID;

import com.chatapp.presence.exception.DependencyUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestUserClient implements UserClient {

    private static final Logger log = LoggerFactory.getLogger(RestUserClient.class);

    /** The standard answer format of the other services (only the parts we read). */
    private record Envelope<T>(boolean success, String message, T data) {
    }

    private record VisibilityAnswer(Boolean visible) {
    }

    private final RestClient restClient;

    public RestUserClient(InternalRestClientFactory factory, @Value("${chatapp.user-service.url}") String userServiceUrl) {
        this.restClient = factory.create(userServiceUrl);
    }

    @Override
    public boolean canSeePresence(UUID viewerId, UUID targetId) {
        try {
            Envelope<VisibilityAnswer> answer = restClient.post()
                    .uri("/internal/users/presence-visibility")
                    .body(Map.of("viewerId", viewerId, "targetId", targetId))
                    .retrieve()
                    .body(new ParameterizedTypeReference<Envelope<VisibilityAnswer>>() { });
            if (answer == null || answer.data() == null || answer.data().visible() == null) {
                throw new DependencyUnavailableException("User service is not available");
            }
            return answer.data().visible();
        } catch (DependencyUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            // Any problem means "we do not know": show nothing rather than risk showing something private.
            log.warn("user-service presence-visibility call failed ({})", e.getClass().getSimpleName());
            throw new DependencyUnavailableException("User service is not available");
        }
    }
}
