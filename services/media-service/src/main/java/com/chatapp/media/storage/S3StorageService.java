package com.chatapp.media.storage;

import java.nio.file.Path;
import java.time.Duration;

import com.chatapp.media.config.MediaProperties;
import com.chatapp.media.exception.StorageUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

/**
 * Talks to the object storage with the S3 API (RustFS locally, AWS S3 in production).
 * Two addresses are used on purpose:
 *  - the S3Client uploads and deletes through the INTERNAL address (inside Docker: http://s3:9000);
 *  - the S3Presigner writes the PUBLIC address into the signed links, because the BROWSER downloads from it.
 */
@Service
public class S3StorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(S3StorageService.class);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final boolean createBucket;

    private volatile boolean bucketReady;

    public S3StorageService(S3Client s3, S3Presigner presigner, MediaProperties properties) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = properties.s3().bucket();
        this.createBucket = properties.s3().createBucket();
    }

    // ----------------------------------------------------------------- bucket

    /**
     * Right after startup, make sure the bucket exists. The storage container may still be starting, so we keep
     * trying in the background for about a minute instead of failing the whole service.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void prepareBucketInBackground() {
        Thread thread = new Thread(() -> {
            for (int attempt = 1; attempt <= 30 && !bucketReady; attempt++) {
                try {
                    ensureBucket();
                } catch (RuntimeException e) {
                    log.info("Storage not ready yet (attempt {}): {}", attempt, e.getClass().getSimpleName());
                    sleep(2000);
                }
            }
            if (!bucketReady) {
                log.warn("The bucket '{}' could not be prepared; it will be tried again on the first upload", bucket);
            }
        }, "bucket-initializer");
        thread.setDaemon(true);
        thread.start();
    }

    /** Creates the bucket if it does not exist (only when S3_CREATE_BUCKET is true). */
    public synchronized void ensureBucket() {
        if (bucketReady) {
            return;
        }
        if (createBucket) {
            try {
                s3.headBucket(b -> b.bucket(bucket));
            } catch (NoSuchBucketException e) {
                s3.createBucket(b -> b.bucket(bucket));
                log.info("Created bucket '{}'", bucket);
            } catch (S3Exception e) {
                if (e.statusCode() == 404) {
                    s3.createBucket(b -> b.bucket(bucket));
                    log.info("Created bucket '{}'", bucket);
                } else {
                    throw e;
                }
            }
        }
        bucketReady = true;
    }

    // ------------------------------------------------------------------- data

    @Override
    public void put(String key, Path file, String contentType) {
        run("upload", () -> {
            ensureBucket();
            s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType), RequestBody.fromFile(file));
        });
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        run("upload", () -> {
            ensureBucket();
            s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(bytes));
        });
    }

    @Override
    public void delete(String key) {
        run("delete", () -> s3.deleteObject(b -> b.bucket(bucket).key(key)));
    }

    @Override
    public SignedLink presignGet(String key, String contentType, String contentDisposition, Duration validFor) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    // The storage answers with exactly these headers, whatever the upload said.
                    .responseContentType(contentType)
                    .responseContentDisposition(contentDisposition)
                    .build();
            PresignedGetObjectRequest presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(validFor)
                    .getObjectRequest(request)
                    .build());
            return new SignedLink(presigned.url().toString(), presigned.expiration());
        } catch (SdkException e) {
            log.warn("Could not sign a download link ({})", e.getClass().getSimpleName());
            throw new StorageUnavailableException("The file storage is not available");
        }
    }

    private void run(String what, Runnable action) {
        try {
            action.run();
        } catch (SdkException e) {
            log.warn("Storage {} failed ({})", what, e.getClass().getSimpleName());
            throw new StorageUnavailableException("The file storage is not available");
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
