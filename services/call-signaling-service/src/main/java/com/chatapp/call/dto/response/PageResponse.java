package com.chatapp.call.dto.response;

import java.util.List;

/** One page of a list, newest first. */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, boolean hasMore) {
}
