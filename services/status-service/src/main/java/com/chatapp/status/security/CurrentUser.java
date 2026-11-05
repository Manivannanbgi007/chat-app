package com.chatapp.status.security;

import java.util.UUID;

import com.chatapp.status.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

/** Reads "who is calling" from the verified token. This is the ONLY source of the caller's id. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
    }
}
