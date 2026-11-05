package com.chatapp.media.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.chatapp.media.service.OrphanCleanupJob;
import com.chatapp.media.support.AbstractIntegrationTest;
import com.chatapp.media.support.Samples;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Uploads that no message ever used are removed after 24 hours; used files are never touched. */
class OrphanCleanupTest extends AbstractIntegrationTest {

    @Autowired
    private OrphanCleanupJob job;

    private final UUID owner = UUID.randomUUID();

    @Test
    void oldUnusedUploadsAreDeletedWithTheirObjects() throws Exception {
        UUID id = uploadOk(owner, "photo.jpg", "image/jpeg", Samples.jpeg(500, 500), "IMAGE");
        String key = objectKeyOf(id);
        String thumbnailKey = thumbnailKeyOf(id);
        ageHours(id, 25);

        int deleted = job.deleteOrphans();

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM media_files WHERE id = ?", Boolean.class, id.toString())).isTrue();
        assertThat(objectExists(key)).isFalse();
        assertThat(objectExists(thumbnailKey)).isFalse();
        assertThat(countLogs(id, "EXPIRE")).isEqualTo(1);
        assertStatus(getAs(owner, "/api/media/" + id), 404);
    }

    @Test
    void filesUsedByAMessageAreNeverDeleted() throws Exception {
        UUID id = uploadOk(owner, "doc.pdf", "application/pdf", Samples.pdf(), "DOCUMENT");
        assertStatus(attach(id, owner, "DOCUMENT", owner + ":old-message-key"), 200);
        ageHours(id, 24 * 30);          // a month old, but a message uses it

        job.deleteOrphans();

        assertThat(statusOf(id)).isEqualTo("ATTACHED");
        assertThat(objectExists(objectKeyOf(id))).isTrue();
        assertThat(countLogs(id, "EXPIRE")).isZero();
    }

    @Test
    void recentUploadsAreKept() throws Exception {
        UUID fresh = uploadOk(owner, "photo.png", "image/png", Samples.png(10, 10), "IMAGE");
        UUID almost = uploadOk(owner, "photo.png", "image/png", Samples.png(10, 10), "IMAGE");
        ageHours(almost, 23);           // not yet 24 hours old

        job.deleteOrphans();

        assertThat(statusOf(fresh)).isEqualTo("UPLOADED");
        assertThat(statusOf(almost)).isEqualTo("UPLOADED");
        assertThat(objectExists(objectKeyOf(fresh))).isTrue();
    }

    @Test
    void runningTheJobTwiceIsHarmless() throws Exception {
        UUID id = uploadOk(owner, "photo.png", "image/png", Samples.png(10, 10), "IMAGE");
        ageHours(id, 48);

        job.deleteOrphans();
        int second = job.deleteOrphans();

        assertThat(statusOf(id)).isEqualTo("DELETED");
        assertThat(countLogs(id, "EXPIRE")).isEqualTo(1);
        assertThat(second).isGreaterThanOrEqualTo(0);
    }

    @Test
    void aFileThatGetsAttachedJustBeforeTheJobRunsSurvives() throws Exception {
        UUID id = uploadOk(owner, "photo.png", "image/png", Samples.png(10, 10), "IMAGE");
        ageHours(id, 30);
        // the attach wins the race: the database only deletes rows that are still UPLOADED
        assertStatus(attach(id, owner, "IMAGE", owner + ":just-in-time-key"), 200);

        job.deleteOrphans();

        assertThat(statusOf(id)).isEqualTo("ATTACHED");
        assertThat(objectExists(objectKeyOf(id))).isTrue();
    }
}
