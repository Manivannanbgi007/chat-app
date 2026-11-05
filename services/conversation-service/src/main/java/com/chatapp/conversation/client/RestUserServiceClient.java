package com.chatapp.conversation.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chatapp.conversation.dto.response.ApiResponseDTO;
import com.chatapp.conversation.dto.response.PublicProfileResponse;
import com.chatapp.conversation.exception.DependencyUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls user-service over HTTP with short timeouts (2 s to connect, 3 s to answer).
 * The header X-Internal-Token proves that the call comes from one of our services.
 */
@Component
public class RestUserServiceClient implements UserServiceClient {

    /** user-service accepts at most 100 ids per summaries request; bigger lists are sent in parts. */
    static final int SUMMARIES_BATCH = 100;

    private static final Logger log = LoggerFactory.getLogger(RestUserServiceClient.class);

    private final RestClient restClient;

    private record GroupAddCheckAnswer(List<UUID> notAllowed) {
    }

    public RestUserServiceClient(@Value("${chatapp.user-service.url}") String baseUrl,
                                 @Value("${chatapp.internal.token}") String internalToken) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(3));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Internal-Token", internalToken)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public List<PublicProfileResponse> findSummaries(UUID viewerId, List<UUID> userIds) {
        List<UUID> distinct = userIds.stream().distinct().toList();
        List<PublicProfileResponse> result = new ArrayList<>();
        for (int start = 0; start < distinct.size(); start += SUMMARIES_BATCH) {
            List<UUID> batch = distinct.subList(start, Math.min(distinct.size(), start + SUMMARIES_BATCH));
            result.addAll(findSummaryBatch(viewerId, batch));
        }
        return result;
    }

    private List<PublicProfileResponse> findSummaryBatch(UUID viewerId, List<UUID> userIds) {
        try {
            ApiResponseDTO<List<PublicProfileResponse>> response = restClient.post()
                    .uri("/internal/users/summaries")
                    .body(Map.of("viewerId", viewerId, "userIds", userIds))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() { });
            return response == null || response.data() == null ? List.of() : response.data();
        } catch (Exception e) {
            throw unavailable(e);
        }
    }

    @Override
    public boolean isBlockedEitherWay(UUID userA, UUID userB) {
        try {
            ApiResponseDTO<Map<String, Boolean>> response = restClient.get()
                    .uri("/internal/blocks?a={a}&b={b}", userA, userB)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() { });
            return response != null && response.data() != null && Boolean.TRUE.equals(response.data().get("blocked"));
        } catch (Exception e) {
            throw unavailable(e);
        }
    }

    @Override
    public List<UUID> groupAddNotAllowed(UUID actorId, List<UUID> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        try {
            ApiResponseDTO<GroupAddCheckAnswer> response = restClient.post()
                    .uri("/internal/users/group-add-check")
                    .body(Map.of("actorId", actorId, "userIds", userIds))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() { });
            if (response == null || response.data() == null || response.data().notAllowed() == null) {
                // We cannot tell who may be added: refuse to guess (a missing answer must not let anybody in).
                throw new IllegalStateException("empty group-add-check answer");
            }
            return response.data().notAllowed();
        } catch (Exception e) {
            throw unavailable(e);
        }
    }

    private DependencyUnavailableException unavailable(Exception e) {
        // Only the type goes to the log: the message could contain request details.
        log.warn("user-service call failed ({})", e.getClass().getSimpleName());
        return new DependencyUnavailableException("A required service is not available right now");
    }
}
