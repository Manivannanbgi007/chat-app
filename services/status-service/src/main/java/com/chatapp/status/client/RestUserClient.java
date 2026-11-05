package com.chatapp.status.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chatapp.status.config.AppProperties;
import com.chatapp.status.dto.response.PublicProfileResponse;
import com.chatapp.status.exception.DependencyUnavailableException;
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

    private record OwnersAnswer(List<UUID> ownerIds) {
    }

    private record PrivacyAnswer(Boolean readReceipts) {
    }

    private final RestClient restClient;

    public RestUserClient(InternalRestClientFactory factory, AppProperties properties) {
        this.restClient = factory.create(properties.services().userUrl());
    }

    @Override
    public List<UUID> contactOwners(UUID viewerId) {
        try {
            ApiEnvelope<OwnersAnswer> answer = restClient.get()
                    .uri("/internal/users/{id}/contact-owners", viewerId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiEnvelope<OwnersAnswer>>() { });
            if (answer == null || answer.data() == null) {
                throw unavailable(null);
            }
            return answer.data().ownerIds() == null ? List.of() : answer.data().ownerIds();
        } catch (DependencyUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
    }

    @Override
    public Relation relation(UUID ownerId, UUID viewerId) {
        try {
            ApiEnvelope<Relation> answer = restClient.get()
                    .uri("/internal/contacts/relation?ownerId={o}&viewerId={v}", ownerId, viewerId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiEnvelope<Relation>>() { });
            if (answer == null || answer.data() == null) {
                throw unavailable(null);
            }
            return answer.data();
        } catch (DependencyUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable(e);
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
            return answer != null && answer.data() != null && !Boolean.FALSE.equals(answer.data().readReceipts());
        } catch (RuntimeException e) {
            // unknown user (404) or user-service down: hide the view rather than fail
            log.debug("Privacy lookup failed ({}), treating read receipts as off", e.getClass().getSimpleName());
            return false;
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
