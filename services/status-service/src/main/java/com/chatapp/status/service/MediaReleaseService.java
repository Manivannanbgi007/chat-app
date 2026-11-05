package com.chatapp.status.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.chatapp.status.client.MediaClient;
import com.chatapp.status.entity.MediaRelease;
import com.chatapp.status.exception.DependencyUnavailableException;
import com.chatapp.status.repository.MediaReleaseRepository;
import com.chatapp.status.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Deletes the files of deleted / expired statuses in media-service ("release").
 *
 * The status row and a media_releases row are written in ONE transaction (see StatusService.delete and
 * StatusCleanupJob), then this class asks media-service to delete the file. Only when media-service confirms is the
 * media_releases row removed; if media-service is down, the row waits and is tried again later (30 s, 1 min, 2 min
 * ... at most 1 h apart). Two instances releasing the same file at the same moment is harmless: a second release of
 * an already deleted file is answered "ok" by media-service.
 */
@Service
public class MediaReleaseService {

    private static final Logger log = LoggerFactory.getLogger(MediaReleaseService.class);
    private static final Duration FIRST_RETRY = Duration.ofSeconds(30);
    private static final Duration MAX_RETRY = Duration.ofHours(1);

    private final MediaReleaseRepository repository;
    private final MediaClient mediaClient;

    public MediaReleaseService(MediaReleaseRepository repository, MediaClient mediaClient) {
        this.repository = repository;
        this.mediaClient = mediaClient;
    }

    /** Tries one release now. Returns true when the file is gone and the row was removed. */
    public boolean attempt(MediaRelease release) {
        try {
            mediaClient.release(release.getMediaId(), release.getOwnerId(), release.getReference());
            repository.deleteById(release.getId());
            return true;
        } catch (DependencyUnavailableException e) {
            release.setAttempts(release.getAttempts() + 1);
            release.setNextAttemptAt(Times.now().plus(retryDelay(release.getAttempts())));
            repository.save(release);
            log.info("Could not release a status file yet (attempt {}), will try again", release.getAttempts());
            return false;
        }
    }

    /** Every release whose next attempt is due (called by the clean-up job). Returns how many succeeded. */
    public int processDue(int batchSize) {
        Instant now = Times.now();
        List<MediaRelease> due = repository.findDue(now, PageRequest.of(0, batchSize));
        int released = 0;
        for (MediaRelease release : due) {
            if (attempt(release)) {
                released++;
            }
        }
        return released;
    }

    /** 30 s, 1 min, 2 min, 4 min ... never more than 1 hour. */
    static Duration retryDelay(int attempts) {
        long seconds = FIRST_RETRY.getSeconds() << Math.min(Math.max(attempts - 1, 0), 10);
        return seconds >= MAX_RETRY.getSeconds() ? MAX_RETRY : Duration.ofSeconds(seconds);
    }
}
