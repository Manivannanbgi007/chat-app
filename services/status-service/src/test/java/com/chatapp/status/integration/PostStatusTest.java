package com.chatapp.status.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.chatapp.status.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Posting statuses: text, photo, video, retries, the limit, and every media-service answer. */
class PostStatusTest extends AbstractIntegrationTest {

    @Test
    void aTextStatusIsSavedAndExpiresIn24Hours() throws Exception {
        Person me = newPerson("Asha");

        MvcResult result = postText(me, "Good morning!", newKey());

        assertStatus(result, 201);
        assertThat(field(result, "$.data.type")).isEqualTo("TEXT");
        assertThat(field(result, "$.data.text")).isEqualTo("Good morning!");
        assertThat(field(result, "$.data.backgroundColor")).isEqualTo("#1D4ED8");
        assertThat(field(result, "$.data.ownerId")).isEqualTo(me.id().toString());
        assertThat(field(result, "$.data.media")).isNull();
        assertThat(field(result, "$.data.caption")).isNull();
        assertThat(field(result, "$.data.viewedByMe")).isEqualTo("true");
        Instant created = Instant.parse(field(result, "$.data.createdAt"));
        Instant expires = Instant.parse(field(result, "$.data.expiresAt"));
        assertThat(Duration.between(created, expires)).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void aPhotoStatusAttachesTheFileWithTheStatusReferenceAndKeepsItsMetadata() throws Exception {
        Person me = newPerson("Asha");
        UUID mediaId = MEDIA.addUpload(me.id(), "IMAGE", true);
        String key = newKey();

        MvcResult result = postMedia(me, "IMAGE", mediaId, "  Sunset  ", key);

        assertStatus(result, 201);
        assertThat(field(result, "$.data.type")).isEqualTo("IMAGE");
        assertThat(field(result, "$.data.caption")).isEqualTo("Sunset");
        assertThat(field(result, "$.data.text")).isNull();
        assertThat(field(result, "$.data.media.kind")).isEqualTo("IMAGE");
        assertThat(field(result, "$.data.media.mimeType")).isEqualTo("image/jpeg");
        assertThat(field(result, "$.data.media.width")).isEqualTo("640");
        assertThat(field(result, "$.data.media.height")).isEqualTo("480");
        assertThat(field(result, "$.data.media.hasThumbnail")).isEqualTo("true");
        assertThat(MEDIA.referenceOf(mediaId)).isEqualTo("status:" + me.id() + ":" + key);
        // the file id is never shown
        assertThat(result.getResponse().getContentAsString()).doesNotContain(mediaId.toString());
    }

    @Test
    void aVideoStatusKeepsTheDurationAndNeedsNoCaption() throws Exception {
        Person me = newPerson("Asha");
        UUID mediaId = MEDIA.addUpload(me.id(), "VIDEO", false);

        MvcResult result = postMedia(me, "VIDEO", mediaId, null, newKey());

        assertStatus(result, 201);
        assertThat(field(result, "$.data.media.kind")).isEqualTo("VIDEO");
        assertThat(field(result, "$.data.media.durationSeconds")).isEqualTo("12");
        assertThat(field(result, "$.data.media.hasThumbnail")).isEqualTo("false");
        assertThat(field(result, "$.data.caption")).isNull();
    }

    @Test
    void theSameKeyAgainReturnsTheSavedStatusWith200() throws Exception {
        Person me = newPerson("Asha");
        String key = newKey();
        MvcResult first = postText(me, "Once", key);
        MvcResult again = postText(me, "Once", key);

        assertStatus(first, 201);
        assertStatus(again, 200);
        assertThat(field(again, "$.data.id")).isEqualTo(field(first, "$.data.id"));
        assertThat(rows("SELECT COUNT(*) FROM statuses WHERE owner_id = ?", me.id().toString())).isEqualTo(1);
    }

    @Test
    void eightParallelPostsWithTheSameKeyCreateOneStatus() throws Exception {
        Person me = newPerson("Asha");
        UUID mediaId = MEDIA.addUpload(me.id(), "IMAGE", true);
        String key = newKey();

        int requests = 8;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            Callable<MvcResult> call = () -> {
                go.await();
                return postMedia(me, "IMAGE", mediaId, "same", key);
            };
            futures.add(pool.submit(call));
        }
        go.countDown();
        List<MvcResult> results = new ArrayList<>();
        for (Future<MvcResult> future : futures) {
            results.add(future.get());
        }
        pool.shutdown();

        assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 201).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 200).count()).isEqualTo(requests - 1);
        assertThat(results.stream().map(r -> {
            try {
                return field(r, "$.data.id");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).distinct()).hasSize(1);
        assertThat(rows("SELECT COUNT(*) FROM statuses WHERE owner_id = ?", me.id().toString())).isEqualTo(1);
    }

    @Test
    void atMost30ActiveStatusesButExpiredOnesDoNotCount() throws Exception {
        Person me = newPerson("Asha");
        UUID first = textStatus(me, "1");
        for (int i = 2; i <= 30; i++) {
            textStatus(me, String.valueOf(i));
        }

        assertError(postText(me, "31", newKey()), 409, "STATUS_LIMIT_REACHED");

        expire(first);
        assertStatus(postText(me, "31 after one expired", newKey()), 201);
    }

    @Test
    void mediaServiceAnswersBecomeTheDocumentedErrors() throws Exception {
        Person me = newPerson("Asha");
        Person other = newPerson("Ravi");

        // somebody else's upload, or an unknown id
        UUID othersFile = MEDIA.addUpload(other.id(), "IMAGE", true);
        assertError(postMedia(me, "IMAGE", othersFile, null, newKey()), 404, "ATTACHMENT_NOT_FOUND");
        assertError(postMedia(me, "IMAGE", UUID.randomUUID(), null, newKey()), 404, "ATTACHMENT_NOT_FOUND");

        // a video posted as a photo
        UUID video = MEDIA.addUpload(me.id(), "VIDEO", false);
        assertError(postMedia(me, "IMAGE", video, null, newKey()), 400, "ATTACHMENT_TYPE_MISMATCH");

        // a file already used by another status
        UUID photo = MEDIA.addUpload(me.id(), "IMAGE", true);
        assertStatus(postMedia(me, "IMAGE", photo, null, newKey()), 201);
        assertError(postMedia(me, "IMAGE", photo, null, newKey()), 409, "ATTACHMENT_ALREADY_USED");

        // media-service down
        MEDIA.down = true;
        assertError(postMedia(me, "IMAGE", MEDIA.addUpload(me.id(), "IMAGE", true), null, newKey()), 503, "DEPENDENCY_UNAVAILABLE");
        MEDIA.down = false;

        assertThat(rows("SELECT COUNT(*) FROM statuses WHERE owner_id = ?", me.id().toString())).isEqualTo(1);
    }

    @Test
    void fieldsThatDoNotFitTheTypeAre400WithAFieldMap() throws Exception {
        Person me = newPerson("Asha");
        MvcResult badColour = post("/api/statuses", Map.of("type", "TEXT", "text", "Hi", "backgroundColor", "#000000",
                "idempotencyKey", newKey()), me);
        assertStatus(badColour, 400);
        assertThat(field(badColour, "$.data.backgroundColor")).isNotNull();

        MvcResult blank = post("/api/statuses", Map.of("type", "TEXT", "text", "   ", "backgroundColor", "#1D4ED8",
                "idempotencyKey", newKey()), me);
        assertStatus(blank, 400);
        assertThat(field(blank, "$.data.text")).isNotNull();

        MvcResult photoWithoutFile = post("/api/statuses", Map.of("type", "IMAGE", "idempotencyKey", newKey()), me);
        assertStatus(photoWithoutFile, 400);
        assertThat(field(photoWithoutFile, "$.data.mediaId")).isNotNull();
        assertThat(rows("SELECT COUNT(*) FROM statuses WHERE owner_id = ?", me.id().toString())).isZero();
    }
}
