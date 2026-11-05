package com.chatapp.user.dto.response;

/** blocked = true when either of the two users has blocked the other. */
public record BlockCheckResponse(boolean blocked) {
}
