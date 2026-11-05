package com.chatapp.media.support;

import com.chatapp.media.config.MediaProperties;

/** The default settings as plain objects, for unit tests that do not start Spring. */
public final class TestProperties {

    private TestProperties() {
    }

    public static MediaProperties defaults() {
        return new MediaProperties(
                new MediaProperties.S3("http://localhost:9010", "http://localhost:9010", "us-east-1",
                        "test-access-key", "test-secret-key-123456", "chat-media", true),
                new MediaProperties.Media(new MediaProperties.Limits(10, 100, 25, 25, 25),
                        12000, 50_000_000L, 320, 300, new MediaProperties.Cleanup(24, 3_600_000L)));
    }
}
