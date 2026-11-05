package com.chatapp.media.enums;

public enum AccessAction {
    UPLOAD,
    VIEW_LINK,
    DOWNLOAD_LINK,
    ATTACH,
    DELETE,
    EXPIRE,
    /** An attached file was deleted because what used it is gone (a status expired or was deleted, Phase 8). */
    RELEASE
}
