package com.chatapp.notification.dto.response;

import java.util.List;

/** One page of a list. hasMore tells the client whether asking for page + 1 makes sense. */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, boolean hasMore) {
}
