package com.chatapp.notification.kafka;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the standard event envelope (see KAFKA_EVENT_DESIGN.md). Every problem with the data becomes an
 * InvalidEventException: such an event can never succeed, so it goes straight to the dead-letter topic.
 */
public final class EventReader {

    private EventReader() {
    }

    public static JsonNode parse(JsonMapper jsonMapper, String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidEventException("Empty event");
        }
        try {
            JsonNode root = jsonMapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw new InvalidEventException("Event is not a JSON object");
            }
            return root;
        } catch (JacksonException e) {
            throw new InvalidEventException("Event is not valid JSON", e);
        }
    }

    public static UUID requireUuid(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new InvalidEventException("Field " + field + " is missing");
        }
        return toUuid(value, field);
    }

    /** A UUID field that may be missing or null (for example "actorId" of an automatic change). */
    public static UUID optionalUuid(JsonNode node, String field) {
        String value = text(node, field);
        return value == null || value.isBlank() ? null : toUuid(value, field);
    }

    public static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    public static JsonNode requireObject(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isObject()) {
            throw new InvalidEventException("Field " + field + " is missing");
        }
        return value;
    }

    /** A required array of UUID strings (may be empty). */
    public static List<UUID> uuidList(JsonNode node, String field) {
        JsonNode array = node == null ? null : node.get(field);
        if (array == null || !array.isArray()) {
            throw new InvalidEventException("Field " + field + " is missing");
        }
        List<UUID> result = new ArrayList<>();
        for (JsonNode item : array) {
            result.add(toUuid(item.asString(), field));
        }
        return result;
    }

    /** A required array of objects, each with a "userId". */
    public static List<UUID> userIdsOfMembers(JsonNode node, String field) {
        JsonNode array = node == null ? null : node.get(field);
        if (array == null || !array.isArray()) {
            throw new InvalidEventException("Field " + field + " is missing");
        }
        List<UUID> result = new ArrayList<>();
        for (JsonNode item : array) {
            result.add(requireUuid(item, "userId"));
        }
        return result;
    }

    private static UUID toUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidEventException("Field " + field + " is not a UUID");
        }
    }
}
