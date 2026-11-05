package com.chatapp.media.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Builds the Content-Disposition header value the storage will send with a download link. */
public final class ContentDispositions {

    private ContentDispositions() {
    }

    /**
     * "attachment" makes the browser download the file; "inline" lets it show images, play audio and video.
     * The file name uses the RFC 5987 form (filename*=UTF-8''...), which is safe for any character.
     */
    public static String of(boolean attachment, String fileName) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
        return (attachment ? "attachment" : "inline") + "; filename*=UTF-8''" + encoded;
    }
}
