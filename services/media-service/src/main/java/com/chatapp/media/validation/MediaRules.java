package com.chatapp.media.validation;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.chatapp.media.config.MediaProperties;
import com.chatapp.media.enums.MediaKind;
import com.chatapp.media.exception.UnsupportedFileException;
import org.springframework.stereotype.Component;

/**
 * The rules of the product: which kinds of file are allowed for which purpose, how big they may be,
 * and whether the file name and the type the browser claimed fit what the file really is.
 */
@Component
public class MediaRules {

    public static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    /** What the file really is, and the mime type we store and later tell the storage to serve it with. */
    public record ResolvedType(FileFormat format, String mimeType) {
    }

    private static final Map<MediaKind, Set<FileFormat>> ALLOWED = new EnumMap<>(MediaKind.class);

    static {
        ALLOWED.put(MediaKind.IMAGE, Set.of(FileFormat.JPEG, FileFormat.PNG, FileFormat.GIF, FileFormat.WEBP));
        ALLOWED.put(MediaKind.VIDEO, Set.of(FileFormat.MP4, FileFormat.MOV, FileFormat.WEBM));
        Set<FileFormat> audio = Set.of(FileFormat.M4A, FileFormat.MP4, FileFormat.MP3, FileFormat.OGG,
                FileFormat.WAV, FileFormat.WEBM);
        ALLOWED.put(MediaKind.AUDIO, audio);
        ALLOWED.put(MediaKind.VOICE, audio);
        ALLOWED.put(MediaKind.DOCUMENT, Set.of(FileFormat.PDF, FileFormat.DOCX, FileFormat.XLSX,
                FileFormat.PPTX, FileFormat.TEXT));
    }

    /** File name extensions that fit each real format. A file without an extension is accepted. */
    private static final Map<FileFormat, Set<String>> EXTENSIONS = new EnumMap<>(FileFormat.class);

    static {
        EXTENSIONS.put(FileFormat.JPEG, Set.of("jpg", "jpeg", "jpe"));
        EXTENSIONS.put(FileFormat.PNG, Set.of("png"));
        EXTENSIONS.put(FileFormat.GIF, Set.of("gif"));
        EXTENSIONS.put(FileFormat.WEBP, Set.of("webp"));
        EXTENSIONS.put(FileFormat.MP4, Set.of("mp4", "m4v", "m4a", "m4b"));
        EXTENSIONS.put(FileFormat.M4A, Set.of("m4a", "m4b", "mp4"));
        EXTENSIONS.put(FileFormat.MOV, Set.of("mov", "qt"));
        EXTENSIONS.put(FileFormat.WEBM, Set.of("webm", "weba"));
        EXTENSIONS.put(FileFormat.MP3, Set.of("mp3"));
        EXTENSIONS.put(FileFormat.OGG, Set.of("ogg", "oga", "opus"));
        EXTENSIONS.put(FileFormat.WAV, Set.of("wav"));
        EXTENSIONS.put(FileFormat.PDF, Set.of("pdf"));
        EXTENSIONS.put(FileFormat.DOCX, Set.of("docx"));
        EXTENSIONS.put(FileFormat.XLSX, Set.of("xlsx"));
        EXTENSIONS.put(FileFormat.PPTX, Set.of("pptx"));
        EXTENSIONS.put(FileFormat.TEXT, Set.of("txt", "csv"));
    }

    /** Types a browser may CLAIM for each stored mime type (browsers are not always exact). */
    private static final Map<String, Set<String>> CLAIMS_ACCEPTED = Map.ofEntries(
            Map.entry("image/jpeg", Set.of("image/jpeg", "image/jpg", "image/pjpeg")),
            Map.entry("image/png", Set.of("image/png", "image/x-png")),
            Map.entry("image/gif", Set.of("image/gif")),
            Map.entry("image/webp", Set.of("image/webp")),
            Map.entry("video/mp4", Set.of("video/mp4", "video/x-m4v")),
            Map.entry("audio/mp4", Set.of("audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac", "video/mp4")),
            Map.entry("video/quicktime", Set.of("video/quicktime")),
            Map.entry("video/webm", Set.of("video/webm", "audio/webm")),
            Map.entry("audio/webm", Set.of("audio/webm", "video/webm")),
            Map.entry("audio/mpeg", Set.of("audio/mpeg", "audio/mp3", "audio/x-mpeg", "audio/mpeg3")),
            Map.entry("audio/ogg", Set.of("audio/ogg", "application/ogg", "audio/opus", "audio/x-ogg", "video/ogg")),
            Map.entry("audio/wav", Set.of("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")),
            Map.entry("application/pdf", Set.of("application/pdf", "application/x-pdf")),
            Map.entry(DOCX, Set.of(DOCX)),
            Map.entry(XLSX, Set.of(XLSX)),
            Map.entry(PPTX, Set.of(PPTX)),
            Map.entry("text/plain", Set.of("text/plain")),
            Map.entry("text/csv", Set.of("text/csv", "application/csv", "text/x-csv", "application/vnd.ms-excel", "text/plain")));

    private final MediaProperties.Limits limits;

    public MediaRules(MediaProperties properties) {
        this.limits = properties.media().limitsMb();
    }

    /** Maximum size of a file of this kind, in bytes. */
    public long maxBytes(MediaKind kind) {
        int megabytes = switch (kind) {
            case IMAGE -> limits.image();
            case VIDEO -> limits.video();
            case DOCUMENT -> limits.document();
            case AUDIO -> limits.audio();
            case VOICE -> limits.voice();
        };
        return megabytes * 1024L * 1024L;
    }

    /**
     * Decides what to do with a file whose real format is known.
     * 415 UNSUPPORTED_TYPE: the format is not allowed for this purpose.
     * 415 CONTENT_MISMATCH: the file name or the claimed Content-Type does not fit what the file really is.
     */
    public ResolvedType resolve(MediaKind purpose, FileFormat detected, String extension, String claimedContentType) {
        if (!ALLOWED.get(purpose).contains(detected)) {
            throw new UnsupportedFileException("This kind of file is not allowed here", "UNSUPPORTED_TYPE");
        }
        String mimeType = mimeTypeFor(purpose, detected, extension);

        if (!extension.isEmpty() && !EXTENSIONS.get(detected).contains(extension)) {
            throw new UnsupportedFileException("The file name does not match the content of the file", "CONTENT_MISMATCH");
        }

        String claimed = normalize(claimedContentType);
        if (!claimed.isEmpty() && !"application/octet-stream".equals(claimed)
                && !CLAIMS_ACCEPTED.getOrDefault(mimeType, Set.of(mimeType)).contains(claimed)) {
            throw new UnsupportedFileException("The file type does not match the content of the file", "CONTENT_MISMATCH");
        }
        return new ResolvedType(detected, mimeType);
    }

    private String mimeTypeFor(MediaKind purpose, FileFormat format, String extension) {
        boolean video = purpose == MediaKind.VIDEO;
        return switch (format) {
            case JPEG -> "image/jpeg";
            case PNG -> "image/png";
            case GIF -> "image/gif";
            case WEBP -> "image/webp";
            case MP4 -> video ? "video/mp4" : "audio/mp4";
            case M4A -> "audio/mp4";
            case MOV -> "video/quicktime";
            case WEBM -> video ? "video/webm" : "audio/webm";
            case MP3 -> "audio/mpeg";
            case OGG -> "audio/ogg";
            case WAV -> "audio/wav";
            case PDF -> "application/pdf";
            case DOCX -> DOCX;
            case XLSX -> XLSX;
            case PPTX -> PPTX;
            case TEXT -> "csv".equals(extension) ? "text/csv" : "text/plain";
        };
    }

    private static String normalize(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        String base = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return base.trim().toLowerCase(Locale.ROOT);
    }
}
