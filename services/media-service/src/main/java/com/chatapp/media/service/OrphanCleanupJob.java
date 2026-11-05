package com.chatapp.media.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.chatapp.media.config.MediaProperties;
import com.chatapp.media.entity.MediaAccessLog;
import com.chatapp.media.entity.MediaFile;
import com.chatapp.media.enums.AccessAction;
import com.chatapp.media.enums.MediaStatus;
import com.chatapp.media.repository.MediaAccessLogRepository;
import com.chatapp.media.repository.MediaFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes uploads that no message ever used (the user picked a file and then cancelled, or sending failed).
 * Files that ARE used by a message (status ATTACHED) are never touched.
 * Runs once an hour by default; files must be older than 24 hours to be deleted.
 */
@Component
public class OrphanCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OrphanCleanupJob.class);
    private static final int BATCH_SIZE = 100;
    private static final int MAX_BATCHES_PER_RUN = 50;

    private final MediaFileRepository fileRepository;
    private final MediaAccessLogRepository accessLogRepository;
    private final MediaService mediaService;
    private final TransactionTemplate transactionTemplate;
    private final Duration maxAge;

    public OrphanCleanupJob(MediaFileRepository fileRepository, MediaAccessLogRepository accessLogRepository,
                            MediaService mediaService, TransactionTemplate transactionTemplate,
                            MediaProperties properties) {
        this.fileRepository = fileRepository;
        this.accessLogRepository = accessLogRepository;
        this.mediaService = mediaService;
        this.transactionTemplate = transactionTemplate;
        this.maxAge = Duration.ofHours(properties.media().cleanup().maxAgeHours());
    }

    /** Public so a test can run it directly. Returns how many uploads were deleted. */
    @Scheduled(fixedDelayString = "${chatapp.media.cleanup.interval-ms}",
            initialDelayString = "${chatapp.media.cleanup.interval-ms}")
    public int deleteOrphans() {
        Instant cutoff = Instant.now().minus(maxAge);
        int deleted = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            List<MediaFile> old = fileRepository.findOlderThan(MediaStatus.UPLOADED, cutoff, PageRequest.of(0, BATCH_SIZE));
            if (old.isEmpty()) {
                break;
            }
            for (MediaFile file : old) {
                if (expire(file)) {
                    deleted++;
                }
            }
        }
        if (deleted > 0) {
            log.info("Deleted {} unused uploads older than {} hours", deleted, maxAge.toHours());
        }
        return deleted;
    }

    /** Marks the row deleted only if it is STILL unused (a message may have just attached it), then removes the bytes. */
    private boolean expire(MediaFile file) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Boolean marked = transactionTemplate.execute(status -> {
            if (fileRepository.deleteIfUnused(file.getId(), now) == 0) {
                return false;
            }
            MediaAccessLog entry = new MediaAccessLog();
            entry.setMediaId(file.getId());
            entry.setUserId(file.getOwnerId());
            entry.setAction(AccessAction.EXPIRE);
            entry.setCreatedAt(now);
            accessLogRepository.save(entry);
            return true;
        });
        if (Boolean.TRUE.equals(marked)) {
            mediaService.removeObjects(file);
            return true;
        }
        return false;
    }
}
