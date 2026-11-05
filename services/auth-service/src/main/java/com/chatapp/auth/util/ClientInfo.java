package com.chatapp.auth.util;

import jakarta.servlet.http.HttpServletRequest;

/** Who is calling: IP address and browser/device description (used for sessions and audit logs). */
public record ClientInfo(String ipAddress, String userAgent) {

    public static ClientInfo from(HttpServletRequest request) {
        // The gateway puts the caller's address in X-Forwarded-For; the first entry is the original client.
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
        return new ClientInfo(truncate(ip, 64), truncate(request.getHeader("User-Agent"), 255));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
