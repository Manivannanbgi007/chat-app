package com.chatapp.media.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

import com.chatapp.media.dto.response.MediaResponse;
import com.chatapp.media.entity.MediaAccessLog;
import com.chatapp.media.entity.MediaFile;
import com.chatapp.media.entity.MediaMetadata;
import com.chatapp.media.enums.AccessAction;
import com.chatapp.media.enums.MediaKind;
import com.chatapp.media.enums.MediaStatus;
import com.chatapp.media.exception.BadRequestException;
import com.chatapp.media.exception.FieldErrorException;
import com.chatapp.media.exception.PayloadTooLargeException;
import com.chatapp.media.exception.UnsupportedFileException;
import com.chatapp.media.mapper.MediaMapper;
import com.chatapp.media.repository.MediaAccessLogRepository;
import com.chatapp.media.repository.MediaFileRepository;
import com.chatapp.media.repository.MediaMetadataRepository;
import com.chatapp.media.storage.StorageService;
import com.chatapp.media.util.FileNameCleaner;
import com.chatapp.media.validation.FileFormat;
import com.chatapp.media.validation.FileTypeDetector;
import com.chatapp.media.validation.ImageInspector;
import com.chatapp.media.validation.MediaRules;
import com.chatapp.media.validation.VirusScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Receives a file and decides whether it is allowed, step by step:
 *  1. purpose, duration, "not empty", size limit of this kind of file
 *  2. the file is copied to a temporary file on disk (so even 100 MB never sit in memory)
 *  3. what the file REALLY is is read from its first bytes; it must be allowed for the purpose and fit its name
 *     and the type the browser claimed
 *  4. images: size limits and a thumbnail (this also proves the image can be read)
 *  5. virus scan hook
 *  6. the file (and thumbnail) are stored in the object storage under a random key
 *  7. the database rows are saved. If this fails, the stored objects are removed again.
 */
@Service
public class MediaUploadService {

    private static final Logger log = LoggerFactory.getLogger(MediaUploadService.class);
    private static final int MAX_DURATION_SECONDS = 7200;

    private final MediaRules rules;
    private final FileTypeDetector detector;
    private final ImageInspector imageInspector;
    private final VirusScanner virusScanner;
    private final StorageService storage;
    private final MediaFileRepository fileRepository;
    private final MediaMetadataRepository metadataRepository;
    private final MediaAccessLogRepository accessLogRepository;
    private final MediaMapper mapper;
    private final TransactionTemplate transactionTemplate;

    public MediaUploadService(MediaRules rules, FileTypeDetector detector, ImageInspector imageInspector,
                              VirusScanner virusScanner, StorageService storage, MediaFileRepository fileRepository,
                              MediaMetadataRepository metadataRepository, MediaAccessLogRepository accessLogRepository,
                              MediaMapper mapper, TransactionTemplate transactionTemplate) {
        this.rules = rules;
        this.detector = detector;
        this.imageInspector = imageInspector;
        this.virusScanner = virusScanner;
        this.storage = storage;
        this.fileRepository = fileRepository;
        this.metadataRepository = metadataRepository;
        this.accessLogRepository = accessLogRepository;
        this.mapper = mapper;
        this.transactionTemplate = transactionTemplate;
    }

    public MediaResponse upload(UUID ownerId, MultipartFile upload, String purposeText, Integer durationSeconds) {
        // 1. the cheap checks first
        MediaKind purpose = parsePurpose(purposeText);
        if (upload == null || upload.isEmpty()) {
            throw new FieldErrorException("file", "is required and must not be empty");
        }
        if (durationSeconds != null && (durationSeconds < 0 || durationSeconds > MAX_DURATION_SECONDS)) {
            throw new FieldErrorException("durationSeconds", "must be between 0 and " + MAX_DURATION_SECONDS);
        }
        long maxBytes = rules.maxBytes(purpose);
        if (upload.getSize() > maxBytes) {
            throw new PayloadTooLargeException("The file is larger than " + (maxBytes / (1024 * 1024)) + " MB");
        }

        String fileName = FileNameCleaner.clean(upload.getOriginalFilename());
        String extension = FileNameCleaner.extension(fileName);

        Path temp = null;
        try {
            // 2. to disk
            temp = Files.createTempFile("media-upload-", ".tmp");
            upload.transferTo(temp);
            long size = Files.size(temp);

            // 3. what is it really?
            FileFormat format = detector.detect(temp, extension).orElseThrow(() ->
                    new UnsupportedFileException("This kind of file is not allowed", "UNSUPPORTED_TYPE"));
            MediaRules.ResolvedType type = rules.resolve(purpose, format, extension, upload.getContentType());

            // 4. images
            ImageInspector.Size imageSize = null;
            byte[] thumbnail = null;
            if (format.isImage()) {
                imageSize = imageInspector.readSize(temp, format);
                thumbnail = imageInspector.createThumbnail(temp, format);
            }

            // 5. virus scan
            virusScanner.scan(temp);

            // 6. store
            String key = newObjectKey();
            String thumbnailKey = thumbnail == null ? null : key + "-thumb";
            storage.put(key, temp, type.mimeType());
            try {
                if (thumbnail != null) {
                    storage.put(thumbnailKey, thumbnail, "image/jpeg");
                }
                // 7. remember
                return save(ownerId, purpose, fileName, type.mimeType(), size, key, imageSize, durationSeconds,
                        thumbnailKey, thumbnail);
            } catch (RuntimeException e) {
                removeQuietly(key);
                if (thumbnailKey != null) {
                    removeQuietly(thumbnailKey);
                }
                throw e;
            }
        } catch (IOException e) {
            log.warn("Could not read the uploaded file ({})", e.getClass().getSimpleName());
            throw new BadRequestException("The file could not be read", "VALIDATION");
        } finally {
            deleteTemp(temp);
        }
    }

    private MediaResponse save(UUID ownerId, MediaKind purpose, String fileName, String mimeType, long size, String key,
                               ImageInspector.Size imageSize, Integer durationSeconds, String thumbnailKey, byte[] thumbnail) {
        return transactionTemplate.execute(status -> {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

            MediaFile file = new MediaFile();
            file.setOwnerId(ownerId);
            file.setKind(purpose);
            file.setOriginalName(fileName);
            file.setMimeType(mimeType);
            file.setSizeBytes(size);
            file.setObjectKey(key);
            file.setStatus(MediaStatus.UPLOADED);
            file.setCreatedAt(now);
            fileRepository.saveAndFlush(file);

            MediaMetadata metadata = new MediaMetadata();
            metadata.setMediaId(file.getId());
            if (imageSize != null) {
                metadata.setWidth(imageSize.width());
                metadata.setHeight(imageSize.height());
            }
            metadata.setDurationSeconds(durationSeconds);
            metadata.setThumbnailObjectKey(thumbnailKey);
            metadata.setThumbnailSizeBytes(thumbnail == null ? null : (long) thumbnail.length);
            metadataRepository.save(metadata);

            MediaAccessLog log = new MediaAccessLog();
            log.setMediaId(file.getId());
            log.setUserId(ownerId);
            log.setAction(AccessAction.UPLOAD);
            log.setCreatedAt(now);
            accessLogRepository.save(log);

            return mapper.toResponse(file, metadata);
        });
    }

    // --------------------------------------------------------------- helpers

    private MediaKind parsePurpose(String purposeText) {
        if (purposeText == null || purposeText.isBlank()) {
            throw new FieldErrorException("purpose", "is required");
        }
        try {
            return MediaKind.valueOf(purposeText.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new FieldErrorException("purpose", "must be IMAGE, VIDEO, DOCUMENT, AUDIO or VOICE");
        }
    }

    /** yyyy/MM/random-uuid: nothing from the user (no name, no id) is part of the key. */
    private String newObjectKey() {
        var now = Instant.now().atZone(ZoneOffset.UTC);
        return "%04d/%02d/%s".formatted(now.getYear(), now.getMonthValue(), UUID.randomUUID());
    }

    private void removeQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not remove the object after a failed upload ({})", e.getClass().getSimpleName());
        }
    }

    private void deleteTemp(Path temp) {
        if (temp != null) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException e) {
                log.warn("Could not delete a temporary upload file");
            }
        }
    }
}
