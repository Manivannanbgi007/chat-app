package com.chatapp.status.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chatapp.status.client.MediaClient;
import com.chatapp.status.config.AppProperties;
import com.chatapp.status.dto.request.CreateStatusRequest;
import com.chatapp.status.dto.response.MediaUrlResponse;
import com.chatapp.status.dto.response.MyStatusResponse;
import com.chatapp.status.dto.response.StatusResponse;
import com.chatapp.status.entity.MediaRelease;
import com.chatapp.status.entity.Status;
import com.chatapp.status.enums.StatusType;
import com.chatapp.status.exception.BadRequestException;
import com.chatapp.status.exception.ConflictException;
import com.chatapp.status.exception.ResourceNotFoundException;
import com.chatapp.status.repository.MediaReleaseRepository;
import com.chatapp.status.repository.StatusRepository;
import com.chatapp.status.repository.StatusViewRepository;
import com.chatapp.status.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Posting, reading, viewing and deleting statuses.
 *
 * Rule for every call to another service (media-service, user-service): it happens OUTSIDE a database transaction,
 * so no database connection waits for the network.
 * An expired status (expiresAt in the past) and a status I may not see give the SAME 404, so nobody can find out
 * which statuses exist.
 */
@Service
public class StatusService {

    private static final Logger log = LoggerFactory.getLogger(StatusService.class);

    /** The answer of create: the status and whether this call created it (201) or found it (200, a retry). */
    public record CreateResult(StatusResponse status, boolean created) {
    }

    private final StatusRepository statusRepository;
    private final StatusViewRepository viewRepository;
    private final MediaReleaseRepository releaseRepository;
    private final PrivacyService privacyService;
    private final ViewerService viewerService;
    private final MediaReleaseService releaseService;
    private final MediaClient mediaClient;
    private final TransactionTemplate transactionTemplate;
    private final AppProperties.Status rules;

    public StatusService(StatusRepository statusRepository, StatusViewRepository viewRepository,
                         MediaReleaseRepository releaseRepository, PrivacyService privacyService,
                         ViewerService viewerService, MediaReleaseService releaseService, MediaClient mediaClient,
                         TransactionTemplate transactionTemplate, AppProperties properties) {
        this.statusRepository = statusRepository;
        this.viewRepository = viewRepository;
        this.releaseRepository = releaseRepository;
        this.privacyService = privacyService;
        this.viewerService = viewerService;
        this.releaseService = releaseService;
        this.mediaClient = mediaClient;
        this.transactionTemplate = transactionTemplate;
        this.rules = properties.status();
    }

    // ------------------------------------------------------------------ post

    /**
     * 1. the fields fit the type; 2. the same idempotency key again -> the saved status (a retry never duplicates);
     * 3. at most 30 active statuses; 4. a photo / video is attached in media-service (owner must be me, kind must
     * match); 5. saved. If two requests with the same key arrive at the same moment, the unique key lets one win and
     * the other returns the winner's status.
     */
    public CreateResult create(UUID ownerId, CreateStatusRequest request) {
        StatusRequestRules.check(request);

        var existing = statusRepository.findByOwnerIdAndIdempotencyKey(ownerId, request.idempotencyKey());
        if (existing.isPresent()) {
            return new CreateResult(StatusMapper.toResponse(existing.get(), true), false);
        }
        if (statusRepository.countActiveByOwner(ownerId, Times.now()) >= rules.maxActive()) {
            throw new ConflictException("You can have at most " + rules.maxActive() + " statuses at the same time",
                    "STATUS_LIMIT_REACHED");
        }

        MediaClient.MediaInfo media = null;
        if (request.type() != StatusType.TEXT) {
            String reference = Status.referenceFor(ownerId, request.idempotencyKey());
            media = mediaClient.attach(request.mediaId(), ownerId, request.type().name(), reference);
            if (media.kind() != null && !media.kind().equals(request.type().name())) {
                // media-service already checks this; a second look costs nothing
                throw new BadRequestException("The file does not match the status type", "ATTACHMENT_TYPE_MISMATCH");
            }
        }

        Status status = newStatus(ownerId, request, media);
        try {
            Status saved = transactionTemplate.execute(tx -> statusRepository.saveAndFlush(status));
            return new CreateResult(StatusMapper.toResponse(saved, true), true);
        } catch (DataIntegrityViolationException e) {
            log.info("Concurrent post with the same idempotency key, returning the saved status");
            Status winner = statusRepository.findByOwnerIdAndIdempotencyKey(ownerId, request.idempotencyKey())
                    .orElseThrow(() -> e);
            return new CreateResult(StatusMapper.toResponse(winner, true), false);
        }
    }

    private Status newStatus(UUID ownerId, CreateStatusRequest request, MediaClient.MediaInfo media) {
        Instant now = Times.now();
        Status status = new Status();
        status.setOwnerId(ownerId);
        status.setType(request.type());
        status.setIdempotencyKey(request.idempotencyKey());
        status.setCreatedAt(now);
        status.setExpiresAt(now.plusSeconds(rules.lifetimeSeconds()));
        if (request.type() == StatusType.TEXT) {
            status.setText(request.text());
            status.setBackgroundColor(request.backgroundColor());
        } else {
            status.setCaption(StatusRequestRules.normalizedCaption(request.caption()));
            status.setMediaId(request.mediaId());
            status.setMimeType(media.mimeType());
            status.setWidth(media.width());
            status.setHeight(media.height());
            status.setDurationSeconds(media.durationSeconds());
            status.setHasThumbnail(Boolean.TRUE.equals(media.hasThumbnail()));
        }
        return status;
    }

    // ------------------------------------------------------------------ mine

    /** My active statuses, oldest first, with the number of viewers I may see. */
    public List<MyStatusResponse> mine(UUID ownerId) {
        List<Status> statuses = statusRepository.findActiveByOwner(ownerId, Times.now());
        Map<UUID, Long> counts = viewerService.viewCounts(ownerId, statuses);
        return statuses.stream()
                .map(status -> MyStatusResponse.of(StatusMapper.toResponse(status, true),
                        counts.getOrDefault(status.getId(), 0L)))
                .toList();
    }

    // ------------------------------------------------------------------ view / media

    /** "I saw it." Safe to repeat; my own status changes nothing. */
    public void view(UUID viewerId, UUID statusId) {
        Status status = visibleStatus(viewerId, statusId);
        if (status.getOwnerId().equals(viewerId)) {
            return;
        }
        viewRepository.insertIgnore(UUID.randomUUID().toString(), status.getId().toString(), viewerId.toString(),
                Times.now());
    }

    /** A short-lived signed link to the photo / video (variant "original" or "thumbnail"). */
    public MediaUrlResponse mediaUrl(UUID viewerId, UUID statusId, String variant) {
        Status status = visibleStatus(viewerId, statusId);
        if (!status.hasMedia() || ("thumbnail".equals(variant) && !status.isHasThumbnail())) {
            throw notFound();
        }
        MediaClient.DownloadLink link = mediaClient.downloadLink(status.getMediaId(), viewerId, variant);
        return new MediaUrlResponse(link.url(), link.expiresAt());
    }

    /** The status if it is active and the viewer may see it; otherwise the same 404 for every reason. */
    private Status visibleStatus(UUID viewerId, UUID statusId) {
        Status status = statusRepository.findActive(statusId, Times.now()).orElseThrow(StatusService::notFound);
        if (!privacyService.maySee(viewerId, status.getOwnerId())) {
            throw notFound();
        }
        return status;
    }

    // ------------------------------------------------------------------ delete

    /**
     * The owner deletes a status: the row (its views go with it) and, in the same transaction, a "release this file"
     * row. After the commit we ask media-service to delete the file; if that fails the clean-up job tries again.
     */
    public void delete(UUID ownerId, UUID statusId) {
        MediaRelease release = transactionTemplate.execute(tx -> {
            Status status = statusRepository.findActive(statusId, Times.now())
                    .filter(s -> s.getOwnerId().equals(ownerId))
                    .orElseThrow(StatusService::notFound);
            statusRepository.delete(status);
            return status.hasMedia() ? releaseRepository.save(MediaRelease.of(status, Times.now())) : null;
        });
        if (release != null) {
            releaseService.attempt(release);
        }
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Status not found");
    }
}
