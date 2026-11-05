package com.chatapp.user.dto.response;

/**
 * contact = the viewer is in the owner's contact list (the owner saved the viewer, not the other way round).
 * blocked = either of the two blocked the other.
 */
public record ContactRelationResponse(boolean contact, boolean blocked) {
}
