package com.chatapp.message.dto.response;

import java.util.List;

/** One page of messages. The cursor is opaque for clients: just send it back. */
public record PageDto<T>(List<T> items, String nextCursor, boolean hasMore) {
}
