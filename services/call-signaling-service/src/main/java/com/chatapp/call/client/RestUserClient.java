package com.chatapp.call.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chatapp.call.config.AppProperties;
import com.chatapp.call.dto.response.PublicProfileResponse;
import com.chatapp.call.exception.DependencyUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Calls user-service's internal API (X-Internal-Token, 2 s / 3 s time-outs, see InternalRestClientFactory). */
@Component
public class RestUserClient implements UserClient {

    /** user-service accepts at most 100 ids per summaries request. */
    private static final int MAX_IDS_PER_REQUEST = 100;

    private static final Logger log = LoggerFactory.getLogger(RestUserClient.class);

    private record BlockAnswer(boolean blocked) {
    }

    private final RestClient restClient;

    public RestUserClient(InternalRestClientFactory factory, AppProperties properties) {
        this.restClient = factory.create(properties.services().userUrl());
    }

    @Override
    public boolean blockedEitherWay(UUID userA, UUID userB) {
        try {
            ApiEnvelope<BlockAnswer> answer = restClient.get()
                    .uri("/internal/blocks?a={a}&b={b}", userA, userB)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiEnvelope<BlockAnswer>>() { });
            if (answer == null || answer.data() == null) {
                throw unavailable(null);
            }
            return answer.data().blocked();
        } catch (DependencyUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
    }

    @Override
    public Map<UUID, PublicProfileResponse> findProfiles(UUID viewerId, Collection<UUID> userIds) {
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(userIds));
        Map<UUID, PublicProfileResponse> byId = new HashMap<>();
        for (int start = 0; start < ids.size(); start += MAX_IDS_PER_REQUEST) {
            List<UUID> batch = ids.subList(start, Math.min(ids.size(), start + MAX_IDS_PER_REQUEST));
            try {
                ApiEnvelope<List<PublicProfileResponse>> answer = restClient.post()
                        .uri("/internal/users/summaries")
                        .body(Map.of("viewerId", viewerId, "userIds", batch))
                        .retrieve()
                        .body(new ParameterizedTypeReference<ApiEnvelope<List<PublicProfileResponse>>>() { });
                if (answer != null && answer.data() != null) {
                    answer.data().forEach(profile -> byId.put(profile.id(), profile));
                }
            } catch (RuntimeException e) {
                throw unavailable(e);
            }
        }
        return byId;
    }

    private DependencyUnavailableException unavailable(Exception e) {
        // Only the type goes to the log: the message could contain request details.
        log.warn("user-service call failed ({})", e == null ? "empty answer" : e.getClass().getSimpleName());
        return new DependencyUnavailableException("A required service is not available right now");
    }
}
