package com.chatapp.status.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.chatapp.status.client.MediaClient;
import com.chatapp.status.entity.MediaRelease;
import com.chatapp.status.exception.DependencyUnavailableException;
import com.chatapp.status.repository.MediaReleaseRepository;
import org.junit.jupiter.api.Test;

/** Releasing files of deleted statuses: done -> row removed; media-service down -> row kept with a later retry. */
class MediaReleaseServiceTest {

    private final MediaReleaseRepository repository = mock(MediaReleaseRepository.class);
    private final MediaClient mediaClient = mock(MediaClient.class);
    private final MediaReleaseService service = new MediaReleaseService(repository, mediaClient);

    private static MediaRelease release() {
        MediaRelease release = new MediaRelease();
        release.setId(UUID.randomUUID());
        release.setMediaId(UUID.randomUUID());
        release.setOwnerId(UUID.randomUUID());
        release.setReference("status:x:key-12345678");
        release.setNextAttemptAt(Instant.now());
        release.setCreatedAt(Instant.now());
        return release;
    }

    @Test
    void aSuccessfulReleaseRemovesTheRow() {
        MediaRelease release = release();

        assertThat(service.attempt(release)).isTrue();

        verify(mediaClient).release(release.getMediaId(), release.getOwnerId(), release.getReference());
        verify(repository).deleteById(release.getId());
    }

    @Test
    void aFailedReleaseKeepsTheRowAndTriesAgainLater() {
        MediaRelease release = release();
        doThrow(new DependencyUnavailableException("down")).when(mediaClient).release(any(), any(), any());
        Instant before = Instant.now();

        assertThat(service.attempt(release)).isFalse();

        assertThat(release.getAttempts()).isEqualTo(1);
        assertThat(release.getNextAttemptAt()).isAfter(before.plusSeconds(25));
        verify(repository).save(release);
        verify(repository, never()).deleteById(any());
    }

    @Test
    void retriesWaitLongerEachTimeButNeverMoreThanAnHour() {
        assertThat(MediaReleaseService.retryDelay(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(MediaReleaseService.retryDelay(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(MediaReleaseService.retryDelay(3)).isEqualTo(Duration.ofSeconds(120));
        assertThat(MediaReleaseService.retryDelay(8)).isEqualTo(Duration.ofHours(1));
        assertThat(MediaReleaseService.retryDelay(500)).isEqualTo(Duration.ofHours(1));
    }
}
