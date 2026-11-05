package com.chatapp.media.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

import com.chatapp.media.support.AbstractIntegrationTest;
import com.chatapp.media.support.Samples;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Uploading each kind of file, for real: the bytes must end up in the storage and come back through a signed link. */
class UploadFlowTest extends AbstractIntegrationTest {

    /** One upload scenario: what we send and what we expect to get back. */
    private record Case(String purpose, String fileName, String claimedType, byte[] content, String expectedMime,
                        boolean thumbnail) {
    }

    private UUID owner = UUID.randomUUID();

    private void checkUploadAndDownload(Case c) throws Exception {
        MvcResult uploaded = upload(owner, c.fileName(), c.claimedType(), c.content(), c.purpose());
        assertStatus(uploaded, 201);

        // the answer
        assertThat(field(uploaded, "$.data.kind")).isEqualTo(c.purpose());
        assertThat(field(uploaded, "$.data.mimeType")).as(c.fileName()).isEqualTo(c.expectedMime());
        assertThat(field(uploaded, "$.data.fileName")).isEqualTo(c.fileName());
        assertThat(Long.parseLong(field(uploaded, "$.data.sizeBytes"))).isEqualTo(c.content().length);
        assertThat(field(uploaded, "$.data.hasThumbnail")).isEqualTo(String.valueOf(c.thumbnail()));
        assertThat(uploaded.getResponse().getContentAsString()).doesNotContain("objectKey").doesNotContain("object_key");

        // the stored bytes, fetched through a signed link like a browser would do
        UUID id = UUID.fromString(field(uploaded, "$.data.id"));
        MvcResult details = getAs(owner, "/api/media/" + id);
        assertStatus(details, 200);
        HttpResponse<byte[]> download = fetch(field(details, "$.data.url"));
        assertThat(download.statusCode()).as(c.fileName()).isEqualTo(200);
        assertThat(download.body()).as(c.fileName()).isEqualTo(c.content());
        assertThat(download.headers().firstValue("content-type")).hasValue(c.expectedMime());
        boolean document = c.purpose().equals("DOCUMENT");
        assertThat(download.headers().firstValue("content-disposition").orElse(""))
                .startsWith(document ? "attachment;" : "inline;")
                .contains("filename*=UTF-8''");

        // the thumbnail (images only)
        if (c.thumbnail()) {
            HttpResponse<byte[]> thumb = fetch(field(details, "$.data.thumbnailUrl"));
            assertThat(thumb.statusCode()).isEqualTo(200);
            assertThat(thumb.headers().firstValue("content-type")).hasValue("image/jpeg");
            var image = ImageIO.read(new ByteArrayInputStream(thumb.body()));
            assertThat(image).isNotNull();
            assertThat(Math.max(image.getWidth(), image.getHeight())).isLessThanOrEqualTo(320);
        } else {
            assertThat(details.getResponse().getContentAsString()).contains("\"thumbnailUrl\":null");
        }
    }

    // ---------------------------------------------------------------- purposes

    @Test
    void imagesAreStoredWithAThumbnail() throws Exception {
        checkUploadAndDownload(new Case("IMAGE", "photo.jpg", "image/jpeg", Samples.jpeg(1200, 600), "image/jpeg", true));
        checkUploadAndDownload(new Case("IMAGE", "diagram.png", "image/png", Samples.png(500, 400), "image/png", true));
        checkUploadAndDownload(new Case("IMAGE", "funny.gif", "image/gif", Samples.gif(64, 64), "image/gif", true));
        // WebP is stored, but Java cannot decode it, so there is no thumbnail
        checkUploadAndDownload(new Case("IMAGE", "pic.webp", "image/webp", Samples.webpExtended(800, 600), "image/webp", false));
    }

    @Test
    void videosAreStoredWithoutAThumbnail() throws Exception {
        checkUploadAndDownload(new Case("VIDEO", "clip.mp4", "video/mp4", Samples.mp4(), "video/mp4", false));
        checkUploadAndDownload(new Case("VIDEO", "movie.mov", "video/quicktime", Samples.mov(), "video/quicktime", false));
        checkUploadAndDownload(new Case("VIDEO", "screen.webm", "video/webm", Samples.webm(), "video/webm", false));
    }

    @Test
    void audioFiles() throws Exception {
        checkUploadAndDownload(new Case("AUDIO", "song.mp3", "audio/mpeg", Samples.mp3WithId3(), "audio/mpeg", false));
        checkUploadAndDownload(new Case("AUDIO", "song.m4a", "audio/x-m4a", Samples.m4a(), "audio/mp4", false));
        checkUploadAndDownload(new Case("AUDIO", "song.ogg", "audio/ogg", Samples.ogg(), "audio/ogg", false));
        checkUploadAndDownload(new Case("AUDIO", "song.wav", "audio/wav", Samples.wav(), "audio/wav", false));
        checkUploadAndDownload(new Case("AUDIO", "song.webm", "audio/webm", Samples.webm(), "audio/webm", false));
    }

    @Test
    void voiceNotesFromTheBrowserRecorder() throws Exception {
        // MediaRecorder in Chrome and Firefox produces WebM or OGG; Safari produces MP4 audio
        checkUploadAndDownload(new Case("VOICE", "voice-note.webm", "audio/webm;codecs=opus", Samples.webm(), "audio/webm", false));
        checkUploadAndDownload(new Case("VOICE", "voice-note.ogg", "audio/ogg;codecs=opus", Samples.ogg(), "audio/ogg", false));
        checkUploadAndDownload(new Case("VOICE", "voice-note.mp4", "audio/mp4", Samples.mp4(), "audio/mp4", false));
    }

    @Test
    void documentsAreDownloadedNotShown() throws Exception {
        checkUploadAndDownload(new Case("DOCUMENT", "report.pdf", "application/pdf", Samples.pdf(), "application/pdf", false));
        checkUploadAndDownload(new Case("DOCUMENT", "letter.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                Samples.docx(), "application/vnd.openxmlformats-officedocument.wordprocessingml.document", false));
        checkUploadAndDownload(new Case("DOCUMENT", "sheet.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                Samples.xlsx(), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", false));
        checkUploadAndDownload(new Case("DOCUMENT", "deck.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                Samples.pptx(), "application/vnd.openxmlformats-officedocument.presentationml.presentation", false));
        checkUploadAndDownload(new Case("DOCUMENT", "notes.txt", "text/plain", Samples.text("hello\nworld"), "text/plain", false));
        checkUploadAndDownload(new Case("DOCUMENT", "table.csv", "text/csv", Samples.text("a,b\n1,2\n"), "text/csv", false));
    }

    // ------------------------------------------------------------ what is stored

    @Test
    void theDetectedTypeIsStoredNotTheClaimedOne() throws Exception {
        MvcResult result = upload(owner, "photo.jpeg", "image/jpg", Samples.jpeg(50, 50), "IMAGE");
        assertStatus(result, 201);
        assertThat(field(result, "$.data.mimeType")).isEqualTo("image/jpeg");

        // no claim at all (or the generic one) is fine as well
        assertStatus(upload(owner, "photo.png", "application/octet-stream", Samples.png(10, 10), "IMAGE"), 201);
        assertStatus(upload(owner, "photo.png", null, Samples.png(10, 10), "IMAGE"), 201);
    }

    @Test
    void databaseRowsAndTheRandomObjectKey() throws Exception {
        MvcResult result = upload(owner, "my photo.jpg", "image/jpeg", Samples.jpeg(300, 200), "IMAGE", null);
        assertStatus(result, 201);
        UUID id = UUID.fromString(field(result, "$.data.id"));

        assertThat(statusOf(id)).isEqualTo("UPLOADED");
        assertThat(jdbc.queryForObject("SELECT owner_id FROM media_files WHERE id = ?", String.class, id.toString()))
                .isEqualTo(owner.toString());
        // yyyy/MM/<uuid>: nothing from the user is in the key (no name, no id)
        String key = objectKeyOf(id);
        assertThat(key).matches("\\d{4}/\\d{2}/[0-9a-f-]{36}").doesNotContain("photo").doesNotContain(owner.toString());
        assertThat(thumbnailKeyOf(id)).isEqualTo(key + "-thumb");
        assertThat(objectExists(key)).isTrue();
        assertThat(objectExists(key + "-thumb")).isTrue();

        assertThat(jdbc.queryForObject("SELECT width FROM media_metadata WHERE media_id = ?", Integer.class, id.toString())).isEqualTo(300);
        assertThat(jdbc.queryForObject("SELECT height FROM media_metadata WHERE media_id = ?", Integer.class, id.toString())).isEqualTo(200);
        assertThat(countLogs(id, "UPLOAD")).isEqualTo(1);
    }

    @Test
    void theClientMeasuredDurationIsStoredForAudioAndVideo() throws Exception {
        MvcResult voice = upload(owner, "voice.webm", "audio/webm", Samples.webm(), "VOICE", 42);
        assertStatus(voice, 201);
        assertThat(field(voice, "$.data.durationSeconds")).isEqualTo("42");

        MvcResult withoutDuration = upload(owner, "clip.mp4", "video/mp4", Samples.mp4(), "VIDEO");
        assertThat(field(withoutDuration, "$.data.durationSeconds")).isNull();
    }

    @Test
    void theFileNameIsCleanedBeforeItIsStored() throws Exception {
        MvcResult result = upload(owner, "../../etc/passwd.png", "image/png", Samples.png(10, 10), "IMAGE");
        assertStatus(result, 201);
        assertThat(field(result, "$.data.fileName")).isEqualTo("passwd.png");

        MvcResult rtl = upload(owner, "photo‮gpj.png", "image/png", Samples.png(10, 10), "IMAGE");
        assertThat(field(rtl, "$.data.fileName")).isEqualTo("photogpj.png");

        MvcResult unicode = upload(owner, "नमस्ते report.pdf", "application/pdf", Samples.pdf(), "DOCUMENT");
        UUID id = UUID.fromString(field(unicode, "$.data.id"));
        String disposition = fetch(field(getAs(owner, "/api/media/" + id), "$.data.url")).headers().firstValue("content-disposition").orElse("");
        String encodedName = disposition.substring(disposition.indexOf("filename*=UTF-8''") + "filename*=UTF-8''".length());
        assertThat(encodedName).startsWith("%E0%A4%A8").endsWith("%20report.pdf").doesNotContain(" ");
    }

    // ---------------------------------------------------------------- rejections

    private int fileCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM media_files", Integer.class);
    }

    private int temporaryUploadFiles() throws Exception {
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return (int) files.filter(p -> p.getFileName().toString().startsWith("media-upload-")).count();
        }
    }

    @Test
    void invalidRequestsAre400() throws Exception {
        MvcResult noPurpose = upload(owner, "a.png", "image/png", Samples.png(5, 5), null);
        assertError(noPurpose, 400, "VALIDATION");
        assertThat(field(noPurpose, "$.data.purpose")).isNotBlank();

        MvcResult badPurpose = upload(owner, "a.png", "image/png", Samples.png(5, 5), "PROFILE");
        assertError(badPurpose, 400, "VALIDATION");
        assertThat(field(badPurpose, "$.data.purpose")).contains("IMAGE");

        MvcResult empty = upload(owner, "a.png", "image/png", new byte[0], "IMAGE");
        assertError(empty, 400, "VALIDATION");
        assertThat(field(empty, "$.data.file")).isNotBlank();

        assertError(upload(owner, "v.webm", "audio/webm", Samples.webm(), "VOICE", -1), 400, "VALIDATION");
        assertError(upload(owner, "v.webm", "audio/webm", Samples.webm(), "VOICE", 7201), 400, "VALIDATION");

        MvcResult noFile = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/api/media/upload").param("purpose", "IMAGE")
                .header("Authorization", bearer(owner))).andReturn();
        assertError(noFile, 400, "VALIDATION");
    }

    @Test
    void aFileOverTheLimitOfItsKindIs413AndNothingIsStored() throws Exception {
        int before = fileCount();
        int tempBefore = temporaryUploadFiles();

        byte[] tooBig = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy(Samples.jpeg(10, 10), 0, tooBig, 0, 100);
        MvcResult result = upload(owner, "huge.jpg", "image/jpeg", tooBig, "IMAGE");

        assertError(result, 413, "FILE_TOO_LARGE");
        assertThat(fileCount()).isEqualTo(before);
        assertThat(temporaryUploadFiles()).isEqualTo(tempBefore);
    }

    @Test
    void programsAndScriptsAreRefusedWhateverTheyAreCalled() throws Exception {
        int before = fileCount();
        int tempBefore = temporaryUploadFiles();

        assertError(upload(owner, "photo.jpg", "image/jpeg", Samples.windowsExecutable(), "IMAGE"), 415, "UNSUPPORTED_TYPE");
        assertError(upload(owner, "setup.exe", "application/x-msdownload", Samples.windowsExecutable(), "DOCUMENT"), 415, "UNSUPPORTED_TYPE");
        assertError(upload(owner, "run.pdf", "application/pdf", Samples.elfExecutable(), "DOCUMENT"), 415, "UNSUPPORTED_TYPE");
        assertError(upload(owner, "notes.txt", "text/plain", Samples.text("<html><script>alert(1)</script></html>"), "DOCUMENT"), 415, "UNSUPPORTED_TYPE");
        assertError(upload(owner, "logo.png", "image/png", Samples.text("<svg xmlns='http://www.w3.org/2000/svg'/>"), "IMAGE"), 415, "UNSUPPORTED_TYPE");
        assertError(upload(owner, "archive.zip", "application/zip", Samples.plainZip(), "DOCUMENT"), 415, "UNSUPPORTED_TYPE");
        assertError(upload(owner, "bomb.docx", "application/octet-stream", Samples.zipWithManyEntries(2500), "DOCUMENT"), 415, "UNSUPPORTED_TYPE");
        assertError(upload(owner, "clip.mkv", "video/x-matroska", Samples.matroska(), "VIDEO"), 415, "UNSUPPORTED_TYPE");

        assertThat(fileCount()).isEqualTo(before);
        assertThat(temporaryUploadFiles()).as("temporary files are always removed").isEqualTo(tempBefore);
    }

    @Test
    void aRealFileOfTheWrongKindForThePurposeIsRefused() throws Exception {
        assertError(upload(owner, "photo.png", "image/png", Samples.png(5, 5), "DOCUMENT"), 415, "UNSUPPORTED_TYPE");
        assertError(upload(owner, "report.pdf", "application/pdf", Samples.pdf(), "IMAGE"), 415, "UNSUPPORTED_TYPE");
        assertError(upload(owner, "song.mp3", "audio/mpeg", Samples.mp3WithId3(), "VIDEO"), 415, "UNSUPPORTED_TYPE");
    }

    @Test
    void theNameOrTheClaimedTypeMustFitTheContent() throws Exception {
        assertError(upload(owner, "photo.jpg", "image/png", Samples.png(5, 5), "IMAGE"), 415, "CONTENT_MISMATCH");     // PNG called .jpg
        assertError(upload(owner, "photo.png", "image/jpeg", Samples.png(5, 5), "IMAGE"), 415, "CONTENT_MISMATCH");     // claims JPEG
        assertError(upload(owner, "report.pdf", "image/jpeg", Samples.pdf(), "DOCUMENT"), 415, "CONTENT_MISMATCH");
        assertError(upload(owner, "letter.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                Samples.xlsx(), "DOCUMENT"), 415, "CONTENT_MISMATCH");                                                    // XLSX called .docx
    }

    @Test
    void brokenOrBombImagesAreRefused() throws Exception {
        assertError(upload(owner, "broken.jpg", "image/jpeg", Samples.brokenJpeg(), "IMAGE"), 400, "INVALID_IMAGE");
        // a tiny file that claims to be 30000 x 30000 pixels: refused without decoding it
        assertError(upload(owner, "bomb.png", "image/png", Samples.pngClaiming(30000, 30000), "IMAGE"), 400, "INVALID_IMAGE");
        assertError(upload(owner, "wide.png", "image/png", Samples.pngClaiming(12001, 10), "IMAGE"), 400, "INVALID_IMAGE");
        assertError(upload(owner, "huge.webp", "image/webp", Samples.webpExtended(20000, 20000), "IMAGE"), 400, "INVALID_IMAGE");
    }

    @Test
    void uploadNeedsAToken() throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/api/media/upload")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "a.png", "image/png", Samples.png(5, 5)))
                .param("purpose", "IMAGE")).andReturn();
        assertStatus(result, 401);
    }

    @Test
    void severalUploadsAtTheSameTimeAllWork() throws Exception {
        List<java.util.concurrent.CompletableFuture<MvcResult>> uploads = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID user = UUID.randomUUID();
            byte[] picture = Samples.jpeg(200 + i, 100 + i);
            uploads.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return upload(user, "parallel.jpg", "image/jpeg", picture, "IMAGE");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        for (var upload : uploads) {
            assertStatus(upload.get(), 201);
        }
    }
}
