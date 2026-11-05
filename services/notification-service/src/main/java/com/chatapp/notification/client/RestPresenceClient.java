package com.chatapp.notification.client;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestPresenceClient implements PresenceClient {

    private static final Logger log = LoggerFactory.getLogger(RestPresenceClient.class);
    private static final int MAX_IDS_PER_CALL = 200;

    private record OnlineAnswer(List<UUID> onlineUserIds) {
    }

    private final RestClient restClient;

    public RestPresenceClient(InternalRestClientFactory factory, @Value("${chatapp.services.presence-url}") String baseUrl) {
        this.restClient = factory.create(baseUrl);
    }

    @Override
    public Set<UUID> online(Collection<UUID> userIds) {
        Set<UUID> online = new HashSet<>();
        List<UUID> ids = userIds.stream().distinct().toList();
        try {
            for (int from = 0; from < ids.size(); from += MAX_IDS_PER_CALL) {
                List<UUID> batch = ids.subList(from, Math.min(ids.size(), from + MAX_IDS_PER_CALL));
                String joined = batch.stream().map(UUID::toString).collect(Collectors.joining(","));
                ApiEnvelope<OnlineAnswer> answer = restClient.get()
                        .uri("/internal/presence/online?userIds={ids}", joined)
                        .retrieve()
                        .body(new ParameterizedTypeReference<ApiEnvelope<OnlineAnswer>>() { });
                if (answer != null && answer.data() != null && answer.data().onlineUserIds() != null) {
                    online.addAll(answer.data().onlineUserIds());
                }
            }
            return online;
        } catch (RuntimeException e) {
            log.warn("presence-service call failed ({}), treating everybody as offline", e.getClass().getSimpleName());
            return Set.of();
        }
    }
}
