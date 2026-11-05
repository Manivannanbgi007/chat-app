package com.chatapp.user.dto.response;

import com.chatapp.user.enums.Visibility;

public record PrivacyResponse(
        Visibility lastSeen,
        Visibility profilePhoto,
        Visibility about,
        Visibility groupAdd,
        boolean readReceipts) {
}
