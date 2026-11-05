package com.chatapp.message.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the content of SYSTEM messages (group changes). The content is a small JSON object that the client turns
 * into a sentence ("Asha added Ravi", "You left"...). The exact shapes are in API_DOCUMENTATION.md section 5.
 * Names are the display names at the time of the change (null when unknown); the client prefers current names.
 */
public final class SystemMessageContent {

    /** A person mentioned in a system message. name may be null. */
    public record UserRef(UUID id, String name) {
    }

    public static final String ONLY_ADMINS_CAN_SEND = "ONLY_ADMINS_CAN_SEND";
    public static final String ONLY_ADMINS_CAN_EDIT = "ONLY_ADMINS_CAN_EDIT";

    private SystemMessageContent() {
    }

    public static Map<String, Object> groupCreated(UserRef actor, String groupName) {
        Map<String, Object> content = start("GROUP_CREATED", actor);
        content.put("name", groupName);
        return content;
    }

    public static Map<String, Object> membersAdded(UserRef actor, List<UserRef> users) {
        return withUsers(start("MEMBERS_ADDED", actor), users);
    }

    public static Map<String, Object> memberRemoved(UserRef actor, UserRef user) {
        return withUsers(start("MEMBER_REMOVED", actor), List.of(user));
    }

    public static Map<String, Object> memberLeft(UserRef actor) {
        return start("MEMBER_LEFT", actor);
    }

    /** actor null = automatic (the last admin left, so the earliest member became admin). */
    public static Map<String, Object> adminAdded(UserRef actor, UserRef user) {
        return withUsers(start("ADMIN_ADDED", actor), List.of(user));
    }

    public static Map<String, Object> adminRemoved(UserRef actor, UserRef user) {
        return withUsers(start("ADMIN_REMOVED", actor), List.of(user));
    }

    public static Map<String, Object> nameChanged(UserRef actor, String newName) {
        Map<String, Object> content = start("NAME_CHANGED", actor);
        content.put("name", newName);
        return content;
    }

    public static Map<String, Object> descriptionChanged(UserRef actor) {
        return start("DESCRIPTION_CHANGED", actor);
    }

    /** setting = ONLY_ADMINS_CAN_SEND or ONLY_ADMINS_CAN_EDIT. */
    public static Map<String, Object> settingChanged(UserRef actor, String setting, boolean value) {
        Map<String, Object> content = start("SETTINGS_CHANGED", actor);
        content.put("setting", setting);
        content.put("value", value);
        return content;
    }

    // A LinkedHashMap keeps the keys in this order in the JSON ("event" first), which makes it easy to read.
    private static Map<String, Object> start(String event, UserRef actor) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("event", event);
        content.put("actor", actor == null ? null : user(actor));
        return content;
    }

    private static Map<String, Object> withUsers(Map<String, Object> content, List<UserRef> users) {
        content.put("users", users.stream().map(SystemMessageContent::user).toList());
        return content;
    }

    private static Map<String, Object> user(UserRef ref) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", ref.id().toString());
        user.put("name", ref.name());
        return user;
    }
}
