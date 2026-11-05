package com.chatapp.conversation.dto.request;

/**
 * Body of PATCH /api/conversations/{id}/group. Every field is optional; a missing (null) field is not changed.
 * description "" removes the description. Name and description are checked in the service (after trimming).
 */
public record UpdateGroupRequest(
        String name,
        String description,
        Boolean onlyAdminsCanSend,
        Boolean onlyAdminsCanEdit) {

    public boolean isEmpty() {
        return name == null && description == null && onlyAdminsCanSend == null && onlyAdminsCanEdit == null;
    }
}
