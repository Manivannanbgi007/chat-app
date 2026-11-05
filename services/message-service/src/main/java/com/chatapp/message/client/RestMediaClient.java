package com.chatapp.message.client;

import java.util.Map;
import java.util.UUID;

import com.chatapp.message.config.AppProperties;
import com.chatapp.message.exception.BadRequestException;
import com.chatapp.message.exception.ConflictException;
import com.chatapp.message.exception.ResourceNotFoundException;
import com.chatapp.message.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

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
                throw new ServiceUnavailableException("Media service is not available");
            }
            return answer.data();
        } catch (HttpClientErrorException e) {
            // media-service answers with distinct statuses; each one has its own meaning for the user
            switch (e.getStatusCode().value()) {
                case 404 -> throw new ResourceNotFoundException("Attachment not found", "ATTACHMENT_NOT_FOUND");
                case 400 -> throw new BadRequestException("The attachment does not match the message type", "ATTACHMENT_TYPE_MISMATCH");
                case 409 -> throw new ConflictException("This attachment is already used by another message", "ATTACHMENT_ALREADY_USED");
                default -> {
                    log.warn("media-service answered {} to an attach request", e.getStatusCode().value());
                    throw new ServiceUnavailableException("Media service is not available");
                }
            }
        } catch (ServiceUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("media-service attach call failed ({})", e.getClass().getSimpleName());
            throw new ServiceUnavailableException("Media service is not available");
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
                throw new ServiceUnavailableException("Media service is not available");
            }
            return answer.data();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 404) {
                throw new ResourceNotFoundException("Attachment not found", "ATTACHMENT_NOT_FOUND");
            }
            log.warn("media-service answered {} to a download-link request", e.getStatusCode().value());
            throw new ServiceUnavailableException("Media service is not available");
        } catch (ServiceUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("media-service download-link call failed ({})", e.getClass().getSimpleName());
            throw new ServiceUnavailableException("Media service is not available");
        }
    }
}
