package com.chatapp.message.dto.response;

/** The saved message, and whether this call created it (true) or it already existed (false). */
public record SendResult(MessageDto message, boolean created) {
}
