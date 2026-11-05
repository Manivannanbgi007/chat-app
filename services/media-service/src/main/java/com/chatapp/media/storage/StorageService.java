package com.chatapp.media.storage;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Where the bytes of the files live (an S3-compatible object storage). A small interface so the rest of the
 * service does not know about AWS, and so another storage could replace it.
 */
public interface StorageService {

    /** Stores a file from disk under the given key (streamed, never loaded fully into memory). */
    void put(String key, Path file, String contentType);

    /** Stores a small piece of data (used for thumbnails). */
    void put(String key, byte[] bytes, String contentType);

    /** Deletes an object. Deleting something that does not exist is not an error. */
    void delete(String key);

    /**
     * A temporary link that lets a browser download the object straight from the storage.
     * The storage is told which content type and content disposition to answer with.
     */
    SignedLink presignGet(String key, String contentType, String contentDisposition, Duration validFor);

    record SignedLink(String url, Instant expiresAt) {
    }
}
