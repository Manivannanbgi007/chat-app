package com.chatapp.status.client;

import java.util.Map;
import java.util.UUID;

import com.chatapp.status.config.AppProperties;
import com.chatapp.status.exception.BadRequestException;
import com.chatapp.status.exception.ConflictException;
import com.chatapp.status.exception.DependencyUnavailableException;
import com.chatapp.status.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Calls media-service's internal API (X-Internal-Token, 2 s / 3 s time-outs, see InternalRestClientFactory). */
@Component
public class RestMediaClient implements MediaClient {

    private static final Logger log = LoggerFactory.getLogger(RestMediaClient.class);

    private final RestClient restClient;

    public RestMediaClient(InternalRestClientFactory factory, AppProperties properties) {
        this.restClient = factory.create(properties.services().mediaUrl());
    }

    @Override
    public MediaInfo attach(UUID mediaId, UUID ownerId, String kind, String reference) {
        try {
            ApiEnvelope<MediaInfo> answer = restClient.post()
                    .uri("/internal/media/{id}/attach", mediaId)
                    .body(Map.of("ownerId", ownerId, "kind", kind, "reference", reference))
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiEnvelope<MediaInfo>>() { });
            if (answer == null || answer.data() == null) {
                throw unavailable("attach", null);
            }
            return answer.data();
        } catch (HttpClientErrorException e) {
            // media-service answers with distinct statuses; each one has its own meaning for the user
            switch (e.getStatusCode().value()) {
                case 404 -> throw new ResourceNotFoundException("Media file not found", "ATTACHMENT_NOT_FOUND");
                case 400 -> throw new BadRequestException("The file does not match the status type", "ATTACHMENT_TYPE_MISMATCH");
                case 409 -> throw new ConflictException("This file is already used", "ATTACHMENT_ALREADY_USED");
                default -> throw unavailable("attach", e);
            }
        } catch (DependencyUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable("attach", e);
        }
    }

    @Override
    public DownloadLink downloadLink(UUID mediaId, UUID requesterId, String variant) {
        try {
            ApiEnvelope<DownloadLink> answer = restClient.post()
                    .uri("/internal/media/{id}/download-link", mediaId)
                    .body(Map.of("requesterId", requesterId, "variant", variant))
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiEnvelope<DownloadLink>>() { });
            if (answer == null || answer.data() == null) {
                throw unavailable("download-link", null);
            }
            return answer.data();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 404) {
                throw new ResourceNotFoundException("Status not found");
            }
            throw unavailable("download-link", e);
        } catch (DependencyUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable("download-link", e);
        }
    }

    @Override
    public void release(UUID mediaId, UUID ownerId, String reference) {
        try {
            restClient.post()
                    .uri("/internal/media/{id}/release", mediaId)
                    .body(Map.of("ownerId", ownerId, "reference", reference))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 404) {
                // unknown, not ours, or attached with another reference: there is nothing we may (or need to) delete
                log.info("media-service has nothing to release for a status file (404)");
                return;
            }
            throw unavailable("release", e);
        } catch (RuntimeException e) {
            throw unavailable("release", e);
        }
    }

    private DependencyUnavailableException unavailable(String call, Exception e) {
        // Only the type / status goes to the log: the message could contain request details.
        String what = e == null ? "empty answer"
                : e instanceof HttpClientErrorException http ? "HTTP " + http.getStatusCode().value()
                : e.getClass().getSimpleName();
        log.warn("media-service {} call failed ({})", call, what);
        return new DependencyUnavailableException("A required service is not available right now");
    }
}
