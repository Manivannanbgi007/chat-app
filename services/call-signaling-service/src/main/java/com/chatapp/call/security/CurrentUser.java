package com.chatapp.call.security;

import java.util.UUID;

import com.chatapp.call.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Reads "who is calling" from the verified token. This is the ONLY source of the caller's id.
 * The device is the token's "sid" claim: one login session = one device (a browser, a phone).
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id(Jwt jwt) {
        return uuidOrUnauthorized(jwt.getSubject());
    }

    /** The login session the token belongs to. Calls are tied to one device of each person. */
    public static UUID deviceId(Jwt jwt) {
        return uuidOrUnauthorized(jwt.getClaimAsString("sid"));
    }

    private static UUID uuidOrUnauthorized(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
    }
}
