package com.chatapp.status.client;

/** The standard answer format of the other services (only the parts we read). */
public record ApiEnvelope<T>(boolean success, String message, T data) {
}
