package com.chatapp.media.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.chatapp.media.support.AbstractIntegrationTest;
import com.chatapp.media.support.Samples;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 8: status-service releases (deletes) the file of an expired or deleted status.
 * The rule that matters most: only with the EXACT reference the file was attached with, so a message's file
 * can never be released. Uses the real storage container, so "deleted" really means the objects are gone.
 */
class ReleaseTest extends AbstractIntegrationTest {

    private final UUID owner = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    private String statusReference(String key) {
        return "status:" + owner + ":" + key;
    }

    private MvcResult release(UUID mediaId, UUID ownerId, String reference) throws Exception {
        return internalPost("/internal/media/" + mediaId + "/release", Map.of("ownerId", ownerId, "reference", reference));
    }

    /** An uploaded photo, attached to a status with the given idempotency key. */
    private UUID statusPhoto(String key) throws Exception {
        UUID id = uploadOk(owner, "status.jpg", "image/jpeg", Samples.jpeg(400, 300), "IMAGE");
        assertStatus(attach(id, owner, "IMAGE", statusReference(key)), 200);
        return id;
    }

    @Test
    void releaseDeletesTheRowAndTheStoredObjects() throws Exception {
        UUID id = statusPhoto("status-key-1");
        String original = objectKeyOf(id);
        String thumbnail = thumbnailKeyOf(id);
        assertThat(objectExists(original)).isTrue();
        assertThat(objectExists(thumbnail)).isTrue();
        assertStatus(downloadLink(id, other, "original"), 200);     // works while the status exists

        MvcResult result = release(id, owner, statusReference("status-key-1"));

        assertStatus(result, 200);
        assertThat(field(result, "$.success")).isEqualTo("true");
        assertThat(statusOf(id)).isEqualTo("DELETED");
        assertThat(objectExists(original)).as("original removed").isFalse();
        assertThat(objectExists(thumbnail)).as("thumbnail removed").isFalse();
        assertThat(countLogs(id, "RELEASE")).isEqualTo(1);
        // nobody can get a link for it any more
        assertError(downloadLink(id, other, "original"), 404, "ATTACHMENT_NOT_FOUND");
        assertError(downloadLink(id, owner, "thumbnail"), 404, "ATTACHMENT_NOT_FOUND");
    }

    @Test
    void releasingAgainIsHarmless() throws Exception {
        UUID id = statusPhoto("status-key-2");

        assertStatus(release(id, owner, statusReference("status-key-2")), 200);
        assertStatus(release(id, owner, statusReference("status-key-2")), 200);

        assertThat(statusOf(id)).isEqualTo("DELETED");
        assertThat(countLogs(id, "RELEASE")).as("released once").isEqualTo(1);
    }

    @Test
    void aWrongReferenceOrAWrongOwnerIs404AndNothingIsDeleted() throws Exception {
        UUID id = statusPhoto("status-key-3");
        String original = objectKeyOf(id);

        MvcResult wrongReference = release(id, owner, statusReference("another-key"));
        MvcResult wrongOwner = release(id, other, statusReference("status-key-3"));
        MvcResult unknown = release(UUID.randomUUID(), owner, statusReference("status-key-3"));

        assertStatus(wrongReference, 404);
        assertStatus(wrongOwner, 404);
        assertStatus(unknown, 404);
        assertThat(field(wrongOwner, "$.message")).isEqualTo(field(unknown, "$.message"));
        assertThat(statusOf(id)).isEqualTo("ATTACHED");
        assertThat(objectExists(original)).isTrue();
        assertThat(countLogs(id, "RELEASE")).isZero();
    }

    @Test
    void anUnattachedUploadCannotBeReleased() throws Exception {
        UUID id = uploadOk(owner, "photo.png", "image/png", Samples.png(20, 20), "IMAGE");

        assertStatus(release(id, owner, statusReference("never-attached")), 404);

        assertThat(statusOf(id)).isEqualTo("UPLOADED");
        assertThat(objectExists(objectKeyOf(id))).isTrue();
    }

    @Test
    void aFileUsedByAMessageCanNeverBeReleased() throws Exception {
        UUID id = uploadOk(owner, "doc.pdf", "application/pdf", Samples.pdf(), "DOCUMENT");
        String messageReference = owner + ":message-key-123";       // how message-service attaches
        assertStatus(attach(id, owner, "DOCUMENT", messageReference), 200);

        // a status-looking reference, a reference built from the same key, and even the right owner: all refused
        assertStatus(release(id, owner, "status:" + owner + ":message-key-123"), 404);
        assertStatus(release(id, owner, "status:" + messageReference), 404);

        assertThat(statusOf(id)).isEqualTo("ATTACHED");
        assertThat(objectExists(objectKeyOf(id))).isTrue();
        assertStatus(downloadLink(id, other, "original"), 200);
    }

    @Test
    void parallelReleasesAllAnswer200AndReleaseOnce() throws Exception {
        UUID id = statusPhoto("status-key-parallel");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Integer>> calls = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                calls.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        go.await();
                        return release(id, owner, statusReference("status-key-parallel")).getResponse().getStatus();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, pool));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (CompletableFuture<Integer> call : calls) {
                statuses.add(call.get());
            }
            assertThat(statuses).as("statuses %s", statuses).containsOnly(200);
        } finally {
            pool.shutdown();
        }
        assertThat(statusOf(id)).isEqualTo("DELETED");
        assertThat(countLogs(id, "RELEASE")).isEqualTo(1);
    }

    @Test
    void theBodyIsValidatedAndTheInternalTokenIsRequired() throws Exception {
        UUID id = statusPhoto("status-key-validation");

        MvcResult noReference = internalPost("/internal/media/" + id + "/release", Map.of("ownerId", owner));
        MvcResult blankReference = internalPost("/internal/media/" + id + "/release", Map.of("ownerId", owner, "reference", " "));
        MvcResult noToken = mockMvc.perform(post("/internal/media/" + id + "/release")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("ownerId", owner, "reference", statusReference("status-key-validation")))))
                .andReturn();
        MvcResult userJwt = mockMvc.perform(post("/internal/media/" + id + "/release")
                .header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("ownerId", owner, "reference", statusReference("status-key-validation")))))
                .andReturn();

        assertStatus(noReference, 400);
        assertStatus(blankReference, 400);
        assertStatus(noToken, 401);
        assertStatus(userJwt, 401);
        assertThat(statusOf(id)).isEqualTo("ATTACHED");
    }
}
