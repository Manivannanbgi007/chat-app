package com.chatapp.status.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.chatapp.status.service.StatusCleanupJob;
import com.chatapp.status.support.AbstractIntegrationTest;
import com.chatapp.status.support.FakeMediaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Deleting statuses, the 24 h expiry and the release (deletion) of their files in media-service. */
class LifecycleTest extends AbstractIntegrationTest {

    @Autowired
    private StatusCleanupJob cleanupJob;

    private int releaseRows(UUID mediaId) {
        return rows("SELECT COUNT(*) FROM media_releases WHERE media_id = ?", mediaId.toString());
    }

    // ------------------------------------------------------------ delete

    @Test
    void theOwnerDeletesAStatusItsViewsAndItsFile() throws Exception {
        Person owner = newPerson("Owner");
        Person viewer = newPerson("Viewer");
        contact(owner, viewer);
        UUID[] photo = photoStatus(owner, true);
        assertStatus(post("/api/statuses/" + photo[0] + "/view", null, viewer), 200);

        assertStatus(delete("/api/statuses/" + photo[0], owner), 200);

        assertThat(rows("SELECT COUNT(*) FROM statuses WHERE id = ?", photo[0].toString())).isZero();
        assertThat(rows("SELECT COUNT(*) FROM status_views WHERE status_id = ?", photo[0].toString())).isZero();
        assertThat(MEDIA.isDeleted(photo[1])).isTrue();
        List<FakeMediaService.ReleaseCall> calls = MEDIA.releaseCalls(photo[1]);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).ownerId()).isEqualTo(owner.id().toString());
        assertThat(calls.get(0).reference()).startsWith("status:" + owner.id() + ":");
        assertThat(releaseRows(photo[1])).isZero();

        // gone for everybody, and deleting again is 404
        assertStatus(post("/api/statuses/" + photo[0] + "/view", null, viewer), 404);
        assertStatus(delete("/api/statuses/" + photo[0], owner), 404);
    }

    @Test
    void nobodyElseCanDeleteMyStatus() throws Exception {
        Person owner = newPerson("Owner");
        Person viewer = newPerson("Viewer");
        contact(owner, viewer);
        UUID statusId = textStatus(owner, "mine");

        assertStatus(delete("/api/statuses/" + statusId, viewer), 404);
        assertThat(rows("SELECT COUNT(*) FROM statuses WHERE id = ?", statusId.toString())).isEqualTo(1);
    }

    @Test
    void aDeleteWhileMediaServiceIsDownIsFinishedByTheCleanupJobLater() throws Exception {
        Person owner = newPerson("Owner");
        UUID[] photo = photoStatus(owner, true);

        MEDIA.releaseDown = true;
        assertStatus(delete("/api/statuses/" + photo[0], owner), 200);

        // the status is gone at once; the file waits for another try
        assertThat(rows("SELECT COUNT(*) FROM statuses WHERE id = ?", photo[0].toString())).isZero();
        assertThat(MEDIA.isDeleted(photo[1])).isFalse();
        assertThat(releaseRows(photo[1])).isEqualTo(1);
        assertThat(rows("SELECT attempts FROM media_releases WHERE media_id = ?", photo[1].toString())).isEqualTo(1);

        MEDIA.releaseDown = false;
        jdbc.update("UPDATE media_releases SET next_attempt_at = UTC_TIMESTAMP(6) WHERE media_id = ?", photo[1].toString());
        cleanupJob.run();

        assertThat(MEDIA.isDeleted(photo[1])).isTrue();
        assertThat(releaseRows(photo[1])).isZero();
    }

    // ------------------------------------------------------------ expiry

    @Test
    void anExpiredStatusIsGoneFromEveryEndpointEvenBeforeTheJobRuns() throws Exception {
        Person owner = newPerson("Owner");
        Person viewer = newPerson("Viewer");
        contact(owner, viewer);
        UUID[] photo = photoStatus(owner, true);

        expire(photo[0]);

        assertThat((List<?>) read(get("/api/statuses/feed", viewer), "$.data[?(@.owner.id == '" + owner.id() + "')]")).isEmpty();
        assertThat((List<?>) read(get("/api/statuses/mine", owner), "$.data")).isEmpty();
        assertStatus(post("/api/statuses/" + photo[0] + "/view", null, viewer), 404);
        assertStatus(get("/api/statuses/" + photo[0] + "/media-url", viewer), 404);
        assertStatus(get("/api/statuses/" + photo[0] + "/viewers", owner), 404);
        assertStatus(delete("/api/statuses/" + photo[0], owner), 404);
    }

    @Test
    void theCleanupJobDeletesExpiredStatusesTheirViewsAndTheirFiles() throws Exception {
        Person owner = newPerson("Owner");
        Person viewer = newPerson("Viewer");
        contact(owner, viewer);
        UUID[] photo = photoStatus(owner, true);
        UUID text = textStatus(owner, "text");
        UUID keep = textStatus(owner, "still active");
        assertStatus(post("/api/statuses/" + photo[0] + "/view", null, viewer), 200);
        expire(photo[0]);
        expire(text);

        cleanupJob.run();

        assertThat(rows("SELECT COUNT(*) FROM statuses WHERE id IN (?, ?)", photo[0].toString(), text.toString())).isZero();
        assertThat(rows("SELECT COUNT(*) FROM status_views WHERE status_id = ?", photo[0].toString())).isZero();
        assertThat(rows("SELECT COUNT(*) FROM statuses WHERE id = ?", keep.toString())).isEqualTo(1);
        assertThat(MEDIA.isDeleted(photo[1])).isTrue();
        assertThat(MEDIA.releaseCalls(photo[1]).get(0).reference()).startsWith("status:" + owner.id() + ":");
        assertThat(releaseRows(photo[1])).isZero();
    }

    @Test
    void aFailingReleaseOfAnExpiredStatusIsRetriedUntilItWorks() throws Exception {
        Person owner = newPerson("Owner");
        UUID[] photo = photoStatus(owner, false);
        expire(photo[0]);

        MEDIA.releaseDown = true;
        cleanupJob.run();
        assertThat(rows("SELECT COUNT(*) FROM statuses WHERE id = ?", photo[0].toString())).isZero();
        assertThat(releaseRows(photo[1])).isEqualTo(1);

        // not due yet: nothing happens
        MEDIA.releaseDown = false;
        cleanupJob.run();
        assertThat(MEDIA.isDeleted(photo[1])).isFalse();

        // due: released and forgotten
        jdbc.update("UPDATE media_releases SET next_attempt_at = UTC_TIMESTAMP(6) WHERE media_id = ?", photo[1].toString());
        cleanupJob.run();
        assertThat(MEDIA.isDeleted(photo[1])).isTrue();
        assertThat(releaseRows(photo[1])).isZero();
        assertThat(MEDIA.releaseCalls(photo[1]).stream().map(FakeMediaService.ReleaseCall::status).toList())
                .containsExactly(503, 200);
    }
}
