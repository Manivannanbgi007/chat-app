package com.chatapp.status.dto.response;

import java.util.List;
import java.util.UUID;

import com.chatapp.status.enums.PrivacyMode;

/** My status privacy: the mode and the excepted / chosen people ([] for CONTACTS). */
public record PrivacyResponse(PrivacyMode mode, List<UUID> userIds) {
}
