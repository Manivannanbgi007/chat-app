package com.chatapp.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** All our own settings from application.yml (prefix "chatapp"), in one typed place. */
@ConfigurationProperties(prefix = "chatapp")
public record MediaProperties(S3 s3, Media media) {

    /** Object storage settings. */
    public record S3(String endpoint, String publicEndpoint, String region, String accessKey, String secretKey,
                     String bucket, boolean createBucket) {
    }

    public record Media(Limits limitsMb, int maxImageSidePx, long maxImagePixels, int thumbnailMaxPx,
                        int linkValidSeconds, Cleanup cleanup) {
    }

    /** Maximum file size per kind, in megabytes. */
    public record Limits(int image, int video, int document, int audio, int voice) {
    }

    public record Cleanup(int maxAgeHours, long intervalMs) {
    }
}
