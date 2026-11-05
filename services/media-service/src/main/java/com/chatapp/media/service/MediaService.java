package com.chatapp.media.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.chatapp.media.config.MediaProperties;
import com.chatapp.media.dto.request.AttachRequest;
import com.chatapp.media.dto.request.DownloadLinkRequest;
import com.chatapp.media.dto.request.ReleaseRequest;
import com.chatapp.media.dto.response.LinkResponse;
import com.chatapp.media.dto.response.MediaResponse;
import com.chatapp.media.dto.response.OwnerMediaResponse;
import com.chatapp.media.entity.MediaAccessLog;
import com.chatapp.media.entity.MediaFile;
import com.chatapp.media.entity.MediaMetadata;
import com.chatapp.media.enums.AccessAction;
import com.chatapp.media.enums.MediaStatus;
import com.chatapp.media.exception.BadRequestException;
import com.chatapp.media.exception.ConflictException;
import com.chatapp.media.exception.ResourceNotFoundException;
import com.chatapp.media.mapper.MediaMapper;
import com.chatapp.media.repository.MediaAccessLogRepository;
import com.chatapp.media.repository.MediaFileRepository;
import com.chatapp.media.repository.MediaMetadataRepository;
import com.chatapp.media.storage.StorageService;
import com.chatapp.media.storage.StorageService.SignedLink;
import com.chatapp.media.util.ContentDispositions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Everything that happens to a file after it was uploaded:
 *  - the uploader looks at it or deletes it (public API)
 *  - message-service attaches it to a message and asks for download links (internal API)
 *  - status-service does the same for statuses, and releases (deletes) the file when the status is gone (Phase 8)
 *
 * Who may DOWNLOAD an attached file is not decided here: message-service decides (it knows who may see the
 * message) and then asks for a link. This service only trusts its internal callers.
 */
@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    private static final String ATTACHMENT_NOT_FOUND = "ATTACHMENT_NOT_FOUND";

    private final MediaFileRepository fileRepository;
    private final MediaMetadataRepository metadataRepository;
    private final MediaAccessLogRepository accessLogRepository;
    private final StorageService storage;
    private final MediaMapper mapper;
    private final TransactionTemplate transactionTemplate;
    private final Duration linkValidity;

    public MediaService(MediaFileRepository fileRepository, MediaMetadataRepository metadataRepository,
                        MediaAccessLogRepository accessLogRepository, StorageService storage, MediaMapper mapper,
                        TransactionTemplate transactionTemplate, MediaProperties properties) {
        this.transactionTemplate = transactionTemplate;
        this.fileRepository = fileRepository;
        this.metadataRepository = metadataRepository;
        this.accessLogRepository = accessLogRepository;
        this.storage = storage;
        this.mapper = mapper;
        this.linkValidity = Duration.ofSeconds(properties.media().linkValidSeconds());
    }

    // ------------------------------------------------- public API (the uploader)

    /** The uploader sees their own file (e.g. a preview before sending). Everybody else gets 404. */
    @Transactional
    public OwnerMediaResponse getOwn(UUID userId, UUID mediaId) {
        MediaFile file = findOwned(userId, mediaId);
        MediaMetadata metadata = metadataOf(file);

        SignedLink original = storage.presignGet(file.getObjectKey(), file.getMimeType(), disposition(file), linkValidity);
        String thumbnailUrl = null;
        if (metadata.hasThumbnail()) {
            thumbnailUrl = storage.presignGet(metadata.getThumbnailObjectKey(), "image/jpeg",
                    ContentDispositions.of(false, "thumbnail.jpg"), linkValidity).url();
        }
        writeLog(file.getId(), userId, AccessAction.VIEW_LINK);
        return new OwnerMediaResponse(mapper.toResponse(file, metadata), original.url(), thumbnailUrl, original.expiresAt());
    }

    /** The uploader deletes a file that no message uses (yet). */
    public void delete(UUID userId, UUID mediaId) {
        // 1. the database part, in one transaction
        MediaFile file = transactionTemplate.execute(status -> markDeleted(userId, mediaId));
        // 2. the bytes in the storage, after the commit (a failure here is only logged: the file is already unreachable)
        removeObjects(file);
    }

    private MediaFile markDeleted(UUID userId, UUID mediaId) {
        MediaFile file = findOwned(userId, mediaId);
        if (file.getStatus() == MediaStatus.ATTACHED) {
            throw new ConflictException("The file is used by a message and cannot be deleted", "MEDIA_IN_USE");
        }
        if (fileRepository.deleteIfUnused(file.getId(), now()) == 0) {
            // somebody attached or deleted it at the same moment: look at the real current state for the right answer
            MediaFile current = fileRepository.findForUpdate(file.getId()).orElseThrow(this::mediaNotFound);
            if (current.getStatus() == MediaStatus.ATTACHED) {
                throw new ConflictException("The file is used by a message and cannot be deleted", "MEDIA_IN_USE");
            }
            throw mediaNotFound();
        }
        writeLog(file.getId(), userId, AccessAction.DELETE);
        return file;
    }

    // ----------------------------------------------- internal API (message-service)

    /**
     * "This file is used by a message." Only the uploader's own, matching, unused file can be attached.
     * Doing it twice with the same reference is fine; a different reference means the file is already taken (409).
     */
    @Transactional
    public MediaResponse attach(UUID mediaId, AttachRequest request) {
        MediaFile file = fileRepository.findById(mediaId)
                .filter(f -> f.getStatus() != MediaStatus.DELETED && f.getOwnerId().equals(request.ownerId()))
                .orElseThrow(() -> new ResourceNotFoundException("Attachment not found", ATTACHMENT_NOT_FOUND));
        if (file.getKind() != request.kind()) {
            throw new BadRequestException("The file is not of the type of the message", "ATTACHMENT_TYPE_MISMATCH");
        }

        if (file.getStatus() == MediaStatus.UPLOADED
                && fileRepository.attachIfUnused(file.getId(), request.reference(), now()) == 1) {
            writeLog(file.getId(), request.ownerId(), AccessAction.ATTACH);
        } else {
            // Not UPLOADED, or somebody else was faster: it is fine only if it was attached with the same reference.
            // (a locking read: a normal read could still show the picture from before the other request committed)
            MediaFile current = fileRepository.findForUpdate(file.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Attachment not found", ATTACHMENT_NOT_FOUND));
            if (current.getStatus() == MediaStatus.DELETED) {
                throw new ResourceNotFoundException("Attachment not found", ATTACHMENT_NOT_FOUND);
            }
            if (!request.reference().equals(current.getAttachReference())) {
                throw new ConflictException("The file is already used by another message", "ATTACHMENT_ALREADY_USED");
            }
        }
        MediaFile result = fileRepository.findById(file.getId()).orElseThrow(this::mediaNotFound);
        return mapper.toResponse(result, metadataOf(result));
    }

    /**
     * "The status that used this file is gone": deletes an ATTACHED file, but only for its uploader and only with
     * the exact reference it was attached with (so a message's file can never be released). Releasing a file that
     * is already deleted is fine (200, nothing happens), so the caller can safely try again. Anything else is 404.
     * Same two steps as delete(): the database row first, the stored bytes after the commit.
     */
    public void release(UUID mediaId, ReleaseRequest request) {
        MediaFile released = transactionTemplate.execute(status -> markReleased(mediaId, request));
        if (released != null) {
            removeObjects(released);
        }
    }

    /** Returns the file when THIS call released it, null when it was already deleted. */
    private MediaFile markReleased(UUID mediaId, ReleaseRequest request) {
        MediaFile file = fileRepository.findById(mediaId)
                .filter(f -> f.getOwnerId().equals(request.ownerId()))
                .orElseThrow(this::mediaNotFound);
        if (fileRepository.releaseIfAttachedWith(file.getId(), request.ownerId(), request.reference(), now()) == 1) {
            writeLog(file.getId(), request.ownerId(), AccessAction.RELEASE);
            return file;
        }
        // Not released by us: either somebody released / deleted it a moment ago (fine, nothing to do),
        // or it is not attached with this reference (404). A locking read shows what really is in the row now.
        MediaFile current = fileRepository.findForUpdate(file.getId()).orElseThrow(this::mediaNotFound);
        if (current.getStatus() == MediaStatus.DELETED) {
            return null;
        }
        throw mediaNotFound();
    }

    /**
     * A signed link for a file that is attached to something (a message, or a status since Phase 8).
     * The caller already checked who may see it.
     */
    @Transactional
    public LinkResponse downloadLink(UUID mediaId, DownloadLinkRequest request) {
        MediaFile file = fileRepository.findById(mediaId)
                .filter(f -> f.getStatus() == MediaStatus.ATTACHED)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment not found", ATTACHMENT_NOT_FOUND));
        MediaMetadata metadata = metadataOf(file);

        SignedLink link;
        if (request.wantsThumbnail()) {
            if (!metadata.hasThumbnail()) {
                throw new ResourceNotFoundException("Attachment not found", ATTACHMENT_NOT_FOUND);
            }
            link = storage.presignGet(metadata.getThumbnailObjectKey(), "image/jpeg",
                    ContentDispositions.of(false, "thumbnail.jpg"), linkValidity);
        } else {
            link = storage.presignGet(file.getObjectKey(), file.getMimeType(), disposition(file), linkValidity);
        }
        writeLog(file.getId(), request.requesterId(), AccessAction.DOWNLOAD_LINK);
        return new LinkResponse(link.url(), link.expiresAt());
    }

    // --------------------------------------------------------------- helpers

    /** Documents are downloaded; images, audio and video are shown in the page. */
    private String disposition(MediaFile file) {
        return ContentDispositions.of(file.getKind() == com.chatapp.media.enums.MediaKind.DOCUMENT, file.getOriginalName());
    }

    private MediaFile findOwned(UUID userId, UUID mediaId) {
        return fileRepository.findById(mediaId)
                .filter(f -> f.getStatus() != MediaStatus.DELETED && f.getOwnerId().equals(userId))
                .orElseThrow(this::mediaNotFound);
    }

    private MediaMetadata metadataOf(MediaFile file) {
        return metadataRepository.findById(file.getId()).orElseGet(() -> {
            MediaMetadata empty = new MediaMetadata();
            empty.setMediaId(file.getId());
            return empty;
        });
    }

    private ResourceNotFoundException mediaNotFound() {
        return new ResourceNotFoundException("Media not found", "MEDIA_NOT_FOUND");
    }

    private void writeLog(UUID mediaId, UUID userId, AccessAction action) {
        MediaAccessLog entry = new MediaAccessLog();
        entry.setMediaId(mediaId);
        entry.setUserId(userId);
        entry.setAction(action);
        entry.setCreatedAt(now());
        accessLogRepository.save(entry);
    }

    /** Removes the stored bytes. A failure is only logged: the row is already DELETED and the file is unreachable. */
    void removeObjects(MediaFile file) {
        try {
            storage.delete(file.getObjectKey());
            metadataRepository.findById(file.getId()).ifPresent(metadata -> {
                if (metadata.hasThumbnail()) {
                    storage.delete(metadata.getThumbnailObjectKey());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Could not remove the stored objects of media {} ({})", file.getId(), e.getClass().getSimpleName());
        }
    }

    private Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
