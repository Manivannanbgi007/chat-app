package com.chatapp.presence.security;

import java.util.UUID;

import com.chatapp.presence.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

/** Reads "who is calling" from the verified token. This is the ONLY source of the caller's id and device. */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** The user: the "sub" claim. */
    public static UUID id(Jwt jwt) {
        return uuidClaim(jwt.getSubject());
    }

    /** The device: the "sid" claim (the login session id). One login session = one device. */
    public static UUID deviceId(Jwt jwt) {
        return uuidClaim(jwt.getClaimAsString("sid"));
    }

    private static UUID uuidClaim(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
    }
}
