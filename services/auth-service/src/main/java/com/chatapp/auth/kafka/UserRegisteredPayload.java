package com.chatapp.auth.kafka;

import java.util.UUID;

/** Data other services need to create their own copy of a new user. No password, of course. */
public record UserRegisteredPayload(UUID userId, String username, String email, String phone, String displayName) {
}
