package com.chatapp.message.util;

import java.util.UUID;

import com.chatapp.message.exception.UnauthorizedException;
import org.springframework.security.oauth2.jwt.Jwt;

/** Reads WHO is calling from the (already validated) access token. Never from the request. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException e) {
            throw new UnauthorizedException("Invalid token", "INVALID_TOKEN");
        }
    }

    /** The login session id doubles as the device id: one session = one device. */
    public static UUID deviceId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getClaimAsString("sid"));
        } catch (RuntimeException e) {
            throw new UnauthorizedException("Invalid token", "INVALID_TOKEN");
        }
    }
}
