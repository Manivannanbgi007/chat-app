package com.chatapp.media.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chatapp.media.enums.MediaKind;
import com.chatapp.media.exception.UnsupportedFileException;
import com.chatapp.media.support.TestProperties;
import org.junit.jupiter.api.Test;

class MediaRulesTest {

    private final MediaRules rules = new MediaRules(TestProperties.defaults());

    private MediaRules.ResolvedType resolve(MediaKind purpose, FileFormat format, String ext, String claimed) {
        return rules.resolve(purpose, format, ext, claimed);
    }

    private void assertRejected(MediaKind purpose, FileFormat format, String ext, String claimed, String errorCode) {
        assertThatThrownBy(() -> resolve(purpose, format, ext, claimed))
                .isInstanceOfSatisfying(UnsupportedFileException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(errorCode));
    }

    // ------------------------------------------------------------------ limits

    @Test
    void sizeLimitsPerKindAreTheDocumentedOnes() {
        assertThat(rules.maxBytes(MediaKind.IMAGE)).isEqualTo(10L * 1024 * 1024);
        assertThat(rules.maxBytes(MediaKind.VIDEO)).isEqualTo(100L * 1024 * 1024);
        assertThat(rules.maxBytes(MediaKind.DOCUMENT)).isEqualTo(25L * 1024 * 1024);
        assertThat(rules.maxBytes(MediaKind.AUDIO)).isEqualTo(25L * 1024 * 1024);
        assertThat(rules.maxBytes(MediaKind.VOICE)).isEqualTo(25L * 1024 * 1024);
    }

    // ---------------------------------------------------------- what is allowed

    @Test
    void imagesAreAllowedAsImagesOnly() {
        assertThat(resolve(MediaKind.IMAGE, FileFormat.JPEG, "jpg", "image/jpeg").mimeType()).isEqualTo("image/jpeg");
        assertThat(resolve(MediaKind.IMAGE, FileFormat.PNG, "png", "image/png").mimeType()).isEqualTo("image/png");
        assertThat(resolve(MediaKind.IMAGE, FileFormat.GIF, "gif", "image/gif").mimeType()).isEqualTo("image/gif");
        assertThat(resolve(MediaKind.IMAGE, FileFormat.WEBP, "webp", "image/webp").mimeType()).isEqualTo("image/webp");
        assertRejected(MediaKind.DOCUMENT, FileFormat.PNG, "png", "image/png", "UNSUPPORTED_TYPE");
        assertRejected(MediaKind.VIDEO, FileFormat.JPEG, "jpg", "image/jpeg", "UNSUPPORTED_TYPE");
        assertRejected(MediaKind.VOICE, FileFormat.GIF, "gif", "image/gif", "UNSUPPORTED_TYPE");
    }

    @Test
    void videoFormats() {
        assertThat(resolve(MediaKind.VIDEO, FileFormat.MP4, "mp4", "video/mp4").mimeType()).isEqualTo("video/mp4");
        assertThat(resolve(MediaKind.VIDEO, FileFormat.MOV, "mov", "video/quicktime").mimeType()).isEqualTo("video/quicktime");
        assertThat(resolve(MediaKind.VIDEO, FileFormat.WEBM, "webm", "video/webm").mimeType()).isEqualTo("video/webm");
        assertRejected(MediaKind.VIDEO, FileFormat.M4A, "m4a", "audio/mp4", "UNSUPPORTED_TYPE");     // audio brand: not a video
        assertRejected(MediaKind.VIDEO, FileFormat.MP3, "mp3", "audio/mpeg", "UNSUPPORTED_TYPE");
        assertRejected(MediaKind.IMAGE, FileFormat.MP4, "mp4", "video/mp4", "UNSUPPORTED_TYPE");
    }

    @Test
    void audioAndVoiceUseAudioTypes() {
        for (MediaKind purpose : new MediaKind[]{MediaKind.AUDIO, MediaKind.VOICE}) {
            assertThat(resolve(purpose, FileFormat.WEBM, "webm", "audio/webm").mimeType()).isEqualTo("audio/webm");
            assertThat(resolve(purpose, FileFormat.OGG, "ogg", "audio/ogg").mimeType()).isEqualTo("audio/ogg");
            assertThat(resolve(purpose, FileFormat.MP3, "mp3", "audio/mpeg").mimeType()).isEqualTo("audio/mpeg");
            assertThat(resolve(purpose, FileFormat.WAV, "wav", "audio/wav").mimeType()).isEqualTo("audio/wav");
            assertThat(resolve(purpose, FileFormat.M4A, "m4a", "audio/mp4").mimeType()).isEqualTo("audio/mp4");
            // a general MP4 container is accepted as audio here (we cannot tell without parsing the tracks)
            assertThat(resolve(purpose, FileFormat.MP4, "mp4", "video/mp4").mimeType()).isEqualTo("audio/mp4");
            assertRejected(purpose, FileFormat.MOV, "mov", "video/quicktime", "UNSUPPORTED_TYPE");
            assertRejected(purpose, FileFormat.PDF, "pdf", "application/pdf", "UNSUPPORTED_TYPE");
        }
    }

    @Test
    void documentFormats() {
        assertThat(resolve(MediaKind.DOCUMENT, FileFormat.PDF, "pdf", "application/pdf").mimeType()).isEqualTo("application/pdf");
        assertThat(resolve(MediaKind.DOCUMENT, FileFormat.DOCX, "docx", MediaRules.DOCX).mimeType()).isEqualTo(MediaRules.DOCX);
        assertThat(resolve(MediaKind.DOCUMENT, FileFormat.XLSX, "xlsx", MediaRules.XLSX).mimeType()).isEqualTo(MediaRules.XLSX);
        assertThat(resolve(MediaKind.DOCUMENT, FileFormat.PPTX, "pptx", MediaRules.PPTX).mimeType()).isEqualTo(MediaRules.PPTX);
        assertThat(resolve(MediaKind.DOCUMENT, FileFormat.TEXT, "txt", "text/plain").mimeType()).isEqualTo("text/plain");
        assertThat(resolve(MediaKind.DOCUMENT, FileFormat.TEXT, "csv", "text/csv").mimeType()).isEqualTo("text/csv");
        assertRejected(MediaKind.IMAGE, FileFormat.PDF, "pdf", "application/pdf", "UNSUPPORTED_TYPE");
    }

    // ---------------------------------------------------- name and claim checks

    @Test
    void fileNameMustFitTheRealContent() {
        // a real PNG called .jpg, a real PDF called .png ...
        assertRejected(MediaKind.IMAGE, FileFormat.PNG, "jpg", "image/png", "CONTENT_MISMATCH");
        assertRejected(MediaKind.DOCUMENT, FileFormat.PDF, "png", "application/pdf", "CONTENT_MISMATCH");
        assertRejected(MediaKind.DOCUMENT, FileFormat.TEXT, "docx", null, "CONTENT_MISMATCH");
        // no extension at all is fine, the real type decides
        assertThat(resolve(MediaKind.IMAGE, FileFormat.PNG, "", "image/png").mimeType()).isEqualTo("image/png");
    }

    @Test
    void theTypeTheBrowserClaimedMustFitToo() {
        assertRejected(MediaKind.IMAGE, FileFormat.PNG, "png", "image/jpeg", "CONTENT_MISMATCH");
        assertRejected(MediaKind.DOCUMENT, FileFormat.PDF, "pdf", "image/jpeg", "CONTENT_MISMATCH");
        assertRejected(MediaKind.IMAGE, FileFormat.JPEG, "jpg", "text/html", "CONTENT_MISMATCH");
    }

    @Test
    void genericOrMissingClaimsAreIgnored() {
        assertThat(resolve(MediaKind.IMAGE, FileFormat.PNG, "png", null).mimeType()).isEqualTo("image/png");
        assertThat(resolve(MediaKind.IMAGE, FileFormat.PNG, "png", "").mimeType()).isEqualTo("image/png");
        assertThat(resolve(MediaKind.IMAGE, FileFormat.PNG, "png", "application/octet-stream").mimeType()).isEqualTo("image/png");
    }

    @Test
    void commonBrowserSpellingsOfTheTypeAreAccepted() {
        assertThat(resolve(MediaKind.IMAGE, FileFormat.JPEG, "jpeg", "image/pjpeg").mimeType()).isEqualTo("image/jpeg");
        assertThat(resolve(MediaKind.AUDIO, FileFormat.WAV, "wav", "audio/x-wav").mimeType()).isEqualTo("audio/wav");
        assertThat(resolve(MediaKind.AUDIO, FileFormat.MP3, "mp3", "audio/mp3").mimeType()).isEqualTo("audio/mpeg");
        assertThat(resolve(MediaKind.AUDIO, FileFormat.M4A, "m4a", "audio/x-m4a").mimeType()).isEqualTo("audio/mp4");
        assertThat(resolve(MediaKind.VOICE, FileFormat.WEBM, "webm", "audio/webm;codecs=opus").mimeType()).isEqualTo("audio/webm");
        assertThat(resolve(MediaKind.VOICE, FileFormat.OGG, "ogg", "AUDIO/OGG").mimeType()).isEqualTo("audio/ogg");
        assertThat(resolve(MediaKind.DOCUMENT, FileFormat.TEXT, "csv", "application/vnd.ms-excel").mimeType()).isEqualTo("text/csv");
    }
}
