package com.chatapp.status.service;

import java.util.List;

import com.chatapp.status.config.AppProperties;
import com.chatapp.status.entity.MediaRelease;
import com.chatapp.status.entity.Status;
import com.chatapp.status.repository.MediaReleaseRepository;
import com.chatapp.status.repository.StatusRepository;
import com.chatapp.status.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes expired statuses and their files. Every read already ignores expired statuses (expiresAt > now), so this
 * job only frees space; it does not decide visibility.
 *
 *  1. In one transaction per batch: lock expired rows (FOR UPDATE SKIP LOCKED, so several instances never take the
 *     same rows), write a "release this file" row for each photo / video, and delete the statuses (views go with
 *     them through the foreign key).
 *  2. Ask media-service to delete the files that are due (MediaReleaseService); a failure is retried on a later run.
 */
@Component
public class StatusCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(StatusCleanupJob.class);

    private final StatusRepository statusRepository;
    private final MediaReleaseRepository releaseRepository;
    private final MediaReleaseService releaseService;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;

    public StatusCleanupJob(StatusRepository statusRepository, MediaReleaseRepository releaseRepository,
                            MediaReleaseService releaseService, TransactionTemplate transactionTemplate,
                            AppProperties properties) {
        this.statusRepository = statusRepository;
        this.releaseRepository = releaseRepository;
        this.releaseService = releaseService;
        this.transactionTemplate = transactionTemplate;
        this.batchSize = properties.status().cleanupBatchSize();
    }

    @Scheduled(fixedDelayString = "${chatapp.status.cleanup-interval-ms:60000}",
            initialDelayString = "${chatapp.status.cleanup-interval-ms:60000}")
    public void run() {
        try {
            int deleted = deleteExpired();
            int released = releaseService.processDue(batchSize);
            if (deleted > 0 || released > 0) {
                log.info("Status clean-up: {} expired statuses deleted, {} files released", deleted, released);
            }
        } catch (RuntimeException e) {
            // never let one bad run stop the schedule; the next run tries again
            log.warn("Status clean-up failed ({}), will try again", e.getClass().getSimpleName());
        }
    }

    /** Deletes every expired status, batch by batch. Returns how many were deleted. */
    public int deleteExpired() {
        int total = 0;
        while (true) {
            Integer deleted = transactionTemplate.execute(tx -> deleteOneBatch());
            int count = deleted == null ? 0 : deleted;
            total += count;
            if (count < batchSize) {
                return total;
            }
        }
    }

    private int deleteOneBatch() {
        List<Status> expired = statusRepository.lockExpired(Times.now(), batchSize);
        if (expired.isEmpty()) {
            return 0;
        }
        for (Status status : expired) {
            if (status.hasMedia()) {
                releaseRepository.save(MediaRelease.of(status, Times.now()));
            }
        }
        statusRepository.deleteByIds(expired.stream().map(status -> status.getId().toString()).toList());
        return expired.size();
    }
}
