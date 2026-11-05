package com.chatapp.media.validation;

/**
 * The real kinds of file this service understands, found by looking at the first bytes of a file.
 * (MP4, WEBM and TEXT are generic on purpose: whether they are video or audio, plain text or CSV
 * depends on the purpose and the file name, see MediaRules.)
 */
public enum FileFormat {
    JPEG,
    PNG,
    GIF,
    WEBP,
    /** ISO media file with a general brand: can hold video or audio. */
    MP4,
    /** ISO media file with an audio brand (M4A, M4B). */
    M4A,
    /** Apple QuickTime movie. */
    MOV,
    /** WebM container: video or audio. */
    WEBM,
    MP3,
    OGG,
    WAV,
    PDF,
    DOCX,
    XLSX,
    PPTX,
    /** Plain text or CSV. */
    TEXT;

    public boolean isDecodableImage() {
        return this == JPEG || this == PNG || this == GIF;
    }

    public boolean isImage() {
        return isDecodableImage() || this == WEBP;
    }
}
