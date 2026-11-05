package com.chatapp.message.enums;

/** All message types of the product. Only TEXT can be sent until media arrives (Phase 5). */
public enum MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    VOICE,
    DOCUMENT,
    LOCATION,
    CONTACT,
    SYSTEM
}
