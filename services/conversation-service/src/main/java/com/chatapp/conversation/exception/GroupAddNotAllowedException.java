package com.chatapp.conversation.exception;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;

/**
 * 403 GROUP_ADD_NOT_ALLOWED: some of the people cannot be added to the group (they do not exist, one of us blocked
 * the other, or their privacy setting refuses me). The answer lists them in data.userIds so the app can say who;
 * the reason is not told on purpose. Nothing was created or added.
 */
public class GroupAddNotAllowedException extends ApiException {

    private final List<UUID> userIds;

    public GroupAddNotAllowedException(List<UUID> userIds) {
        super(HttpStatus.FORBIDDEN, "Some people can't be added to this group", "GROUP_ADD_NOT_ALLOWED");
        this.userIds = List.copyOf(userIds);
    }

    public List<UUID> getUserIds() {
        return userIds;
    }
}
