package com.chatapp.media.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.ByteArrayInputStream;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;

import com.chatapp.media.support.AbstractIntegrationTest;
import com.chatapp.media.support.Samples;
import com.chatapp.media.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** The internal API that message-service uses: attach a file to a message, then get download links for it. */
class AttachAndLinkTest extends AbstractIntegrationTest {

    private final UUID owner = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    private String reference(String key) {
        return owner + ":" + key;
    }

    // ------------------------------------------------------------------ attach

    @Test
    void attachMarksTheFileAsUsed() throws Exception {
        UUID id = uploadOk(owner, "photo.jpg", "image/jpeg", Samples.jpeg(100, 100), "IMAGE");

        MvcResult result = attach(id, owner, "IMAGE", reference("message-key-1"));

        assertStatus(result, 200);
        assertThat(field(result, "$.data.id")).isEqualTo(id.toString());
        assertThat(field(result, "$.data.kind")).isEqualTo("IMAGE");
        assertThat(field(result, "$.data.hasThumbnail")).isEqualTo("true");
        assertThat(statusOf(id)).isEqualTo("ATTACHED");
        assertThat(jdbc.queryForObject("SELECT attach_reference FROM media_files WHERE id = ?", String.class, id.toString()))
                .isEqualTo(reference("message-key-1"));
        assertThat(countLogs(id, "ATTACH")).isEqualTo(1);
    }

    @Test
    void onlyTheUploaderCanAttachAndOthersLearnNothing() throws Exception {
        UUID id = uploadOk(owner, "photo.png", "image/png", Samples.png(10, 10), "IMAGE");

        MvcResult wrongOwner = attach(id, other, "IMAGE", other + ":key-1234567890");
        MvcResult unknown = attach(UUID.randomUUID(), owner, "IMAGE", reference("key-1234567890"));

        assertError(wrongOwner, 404, "ATTACHMENT_NOT_FOUND");
        assertError(unknown, 404, "ATTACHMENT_NOT_FOUND");
        assertThat(field(wrongOwner, "$.message")).isEqualTo(field(unknown, "$.message"));
        assertThat(statusOf(id)).isEqualTo("UPLOADED");
    }

    @Test
    void theKindMustMatchTheUpload() throws Exception {
        UUID id = uploadOk(owner, "photo.png", "image/png", Samples.png(10, 10), "IMAGE");

        assertError(attach(id, owner, "DOCUMENT", reference("key-1234567890")), 400, "ATTACHMENT_TYPE_MISMATCH");
        assertError(attach(id, owner, "VOICE", reference("key-1234567890")), 400, "ATTACHMENT_TYPE_MISMATCH");
        assertThat(statusOf(id)).isEqualTo("UPLOADED");
    }

    @Test
    void attachingTheSameFileAgainWithTheSameReferenceIsFine() throws Exception {
        UUID id = uploadOk(owner, "doc.pdf", "application/pdf", Samples.pdf(), "DOCUMENT");

        assertStatus(attach(id, owner, "DOCUMENT", reference("retry-key-123")), 200);
        assertStatus(attach(id, owner, "DOCUMENT", reference("retry-key-123")), 200);     // a retry of the same message

        assertThat(countLogs(id, "ATTACH")).as("attached once").isEqualTo(1);
    }

    @Test
    void aFileUsedByOneMessageCannotBeUsedByAnotherOne() throws Exception {
        UUID id = uploadOk(owner, "doc.pdf", "application/pdf", Samples.pdf(), "DOCUMENT");
        assertStatus(attach(id, owner, "DOCUMENT", reference("first-message-key")), 200);

        MvcResult second = attach(id, owner, "DOCUMENT", reference("second-message-key"));

        assertError(second, 409, "ATTACHMENT_ALREADY_USED");
        assertThat(jdbc.queryForObject("SELECT attach_reference FROM media_files WHERE id = ?", String.class, id.toString()))
                .isEqualTo(reference("first-message-key"));
    }

    @Test
    void parallelAttachesWithDifferentReferencesHaveExactlyOneWinner() throws Exception {
        UUID id = uploadOk(owner, "photo.jpg", "image/jpeg", Samples.jpeg(50, 50), "IMAGE");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Integer>> calls = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                String reference = reference("parallel-key-" + i);
                calls.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        go.await();
                        return attach(id, owner, "IMAGE", reference).getResponse().getStatus();
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
            assertThat(statuses.stream().filter(s -> s == 200).count()).as("winners %s", statuses).isEqualTo(1);
            assertThat(statuses.stream().filter(s -> s == 409).count()).as("losers %s", statuses).isEqualTo(threads - 1);
        } finally {
            pool.shutdown();
        }
        assertThat(countLogs(id, "ATTACH")).isEqualTo(1);
    }

    @Test
    void parallelAttachesWithTheSameReferenceAreAllFine() throws Exception {
        UUID id = uploadOk(owner, "photo.jpg", "image/jpeg", Samples.jpeg(50, 50), "IMAGE");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<CompletableFuture<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                calls.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return attach(id, owner, "IMAGE", reference("same-key-12345")).getResponse().getStatus();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, pool));
            }
            for (CompletableFuture<Integer> call : calls) {
                assertThat(call.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void aDeletedFileCannotBeAttached() throws Exception {
        UUID id = uploadOk(owner, "photo.png", "image/png", Samples.png(10, 10), "IMAGE");
        assertStatus(deleteAs(owner, "/api/media/" + id), 200);

        assertError(attach(id, owner, "IMAGE", reference("late-key-12345")), 404, "ATTACHMENT_NOT_FOUND");
    }

    @Test
    void attachChecksItsBody() throws Exception {
        UUID id = uploadOk(owner, "photo.png", "image/png", Samples.png(10, 10), "IMAGE");

        assertStatus(internalPost("/internal/media/" + id + "/attach", Map.of("kind", "IMAGE", "reference", "x")), 400);
        assertStatus(internalPost("/internal/media/" + id + "/attach", Map.of("ownerId", owner, "kind", "BANANA", "reference", "x")), 400);
        assertStatus(internalPost("/internal/media/" + id + "/attach", Map.of("ownerId", owner, "kind", "IMAGE", "reference", "")), 400);
        assertThat(statusOf(id)).isEqualTo("UPLOADED");
    }

    // ----------------------------------------------------------- download links

    @Test
    void anAttachedFileGivesAWorkingOriginalLink() throws Exception {
        byte[] content = Samples.pdf();
        UUID id = uploadOk(owner, "contract.pdf", "application/pdf", content, "DOCUMENT");
        assertStatus(attach(id, owner, "DOCUMENT", reference("doc-message-1")), 200);

        MvcResult link = downloadLink(id, other, "original");

        assertStatus(link, 200);
        HttpResponse<byte[]> download = fetch(field(link, "$.data.url"));
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(content);
        assertThat(download.headers().firstValue("content-type")).hasValue("application/pdf");
        assertThat(download.headers().firstValue("content-disposition").orElse(""))
                .startsWith("attachment;").contains("contract.pdf");
        assertThat(java.time.Instant.parse(field(link, "$.data.expiresAt"))).isAfter(java.time.Instant.now());
    }

    @Test
    void theThumbnailVariantGivesTheSmallJpeg() throws Exception {
        UUID id = uploadOk(owner, "photo.png", "image/png", Samples.png(1000, 700), "IMAGE");
        assertStatus(attach(id, owner, "IMAGE", reference("img-message-1")), 200);

        MvcResult link = downloadLink(id, other, "thumbnail");

        assertStatus(link, 200);
        HttpResponse<byte[]> download = fetch(field(link, "$.data.url"));
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.headers().firstValue("content-type")).hasValue("image/jpeg");
        var image = ImageIO.read(new ByteArrayInputStream(download.body()));
        assertThat(image.getWidth()).isEqualTo(320);
        assertThat(image.getHeight()).isEqualTo(224);
    }

    @Test
    void imagesAreShownInlineAndVoiceNotesToo() throws Exception {
        UUID image = uploadOk(owner, "photo.png", "image/png", Samples.png(30, 30), "IMAGE");
        UUID voice = uploadOk(owner, "voice-note.webm", "audio/webm", Samples.webm(), "VOICE");
        assertStatus(attach(image, owner, "IMAGE", reference("inline-1-12345")), 200);
        assertStatus(attach(voice, owner, "VOICE", reference("inline-2-12345")), 200);

        for (UUID id : List.of(image, voice)) {
            HttpResponse<byte[]> download = fetch(field(downloadLink(id, other, "original"), "$.data.url"));
            assertThat(download.headers().firstValue("content-disposition").orElse("")).startsWith("inline;");
        }
    }

    @Test
    void noThumbnailMeansNotFound() throws Exception {
        UUID doc = uploadOk(owner, "doc.pdf", "application/pdf", Samples.pdf(), "DOCUMENT");
        UUID webp = uploadOk(owner, "pic.webp", "image/webp", Samples.webpExtended(100, 100), "IMAGE");
        assertStatus(attach(doc, owner, "DOCUMENT", reference("nothumb-1-1234")), 200);
        assertStatus(attach(webp, owner, "IMAGE", reference("nothumb-2-1234")), 200);

        assertError(downloadLink(doc, other, "thumbnail"), 404, "ATTACHMENT_NOT_FOUND");
        assertError(downloadLink(webp, other, "thumbnail"), 404, "ATTACHMENT_NOT_FOUND");
        assertStatus(downloadLink(webp, other, "original"), 200);
    }

    @Test
    void aFileThatIsNotAttachedHasNoDownloadLinkEvenForItsOwner() throws Exception {
        UUID id = uploadOk(owner, "photo.png", "image/png", Samples.png(10, 10), "IMAGE");

        assertError(downloadLink(id, owner, "original"), 404, "ATTACHMENT_NOT_FOUND");
        assertError(downloadLink(UUID.randomUUID(), owner, "original"), 404, "ATTACHMENT_NOT_FOUND");
    }

    @Test
    void aDeletedFileHasNoLink() throws Exception {
        UUID id = uploadOk(owner, "photo.png", "image/png", Samples.png(10, 10), "IMAGE");
        assertStatus(deleteAs(owner, "/api/media/" + id), 200);
        assertError(downloadLink(id, owner, "original"), 404, "ATTACHMENT_NOT_FOUND");
    }

    @Test
    void everyLinkHandedOutIsLoggedWithWhoAskedForIt() throws Exception {
        UUID id = uploadOk(owner, "photo.png", "image/png", Samples.png(10, 10), "IMAGE");
        assertStatus(attach(id, owner, "IMAGE", reference("log-message-1")), 200);

        downloadLink(id, other, "original");
        downloadLink(id, owner, "thumbnail");

        assertThat(countLogs(id, "DOWNLOAD_LINK")).isEqualTo(2);
        List<String> users = jdbc.queryForList(
                "SELECT user_id FROM media_access_logs WHERE media_id = ? AND action = 'DOWNLOAD_LINK'", String.class, id.toString());
        assertThat(users).containsExactlyInAnyOrder(other.toString(), owner.toString());
    }

    @Test
    void onlyOriginalAndThumbnailAreValidVariants() throws Exception {
        UUID id = uploadOk(owner, "photo.png", "image/png", Samples.png(10, 10), "IMAGE");
        assertStatus(attach(id, owner, "IMAGE", reference("variant-msg-1")), 200);

        assertStatus(downloadLink(id, other, "huge"), 400);
        assertStatus(downloadLink(id, other, "ORIGINAL"), 400);
        assertStatus(internalPost("/internal/media/" + id + "/download-link", Map.of("variant", "original")), 400);
    }

    // ---------------------------------------------------------- who may call what

    @Test
    void internalEndpointsNeedTheInternalToken() throws Exception {
        UUID id = uploadOk(owner, "photo.png", "image/png", Samples.png(10, 10), "IMAGE");
        String body = jsonMapper.writeValueAsString(Map.of("ownerId", owner, "kind", "IMAGE", "reference", reference("token-msg-123")));

        MvcResult none = mockMvc.perform(post("/internal/media/" + id + "/attach")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        MvcResult wrong = mockMvc.perform(post("/internal/media/" + id + "/attach").header("X-Internal-Token", "nope")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();

        assertStatus(none, 401);
        assertStatus(wrong, 401);
        assertThat(statusOf(id)).isEqualTo("UPLOADED");
    }

    @Test
    void aUserTokenDoesNotOpenInternalEndpoints() throws Exception {
        UUID id = uploadOk(owner, "photo.png", "image/png", Samples.png(10, 10), "IMAGE");
        String body = jsonMapper.writeValueAsString(Map.of("ownerId", owner, "kind", "IMAGE", "reference", reference("jwt-msg-12345")));

        MvcResult result = mockMvc.perform(post("/internal/media/" + id + "/attach")
                .header("Authorization", "Bearer " + TestJwt.token(owner))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();

        assertStatus(result, 401);
        assertThat(statusOf(id)).isEqualTo("UPLOADED");
    }

    @Test
    void theInternalTokenDoesNotOpenUserEndpoints() throws Exception {
        UUID id = uploadOk(owner, "photo.png", "image/png", Samples.png(10, 10), "IMAGE");

        MvcResult get = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/media/" + id).header("X-Internal-Token", TestJwt.INTERNAL_TOKEN)).andReturn();
        MvcResult upload = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/api/media/upload")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "a.png", "image/png", Samples.png(5, 5)))
                .param("purpose", "IMAGE").header("X-Internal-Token", TestJwt.INTERNAL_TOKEN)).andReturn();

        assertStatus(get, 401);
        assertStatus(upload, 401);
    }
}
