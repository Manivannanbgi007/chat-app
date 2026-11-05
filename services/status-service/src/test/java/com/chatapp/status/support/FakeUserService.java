package com.chatapp.status.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A tiny fake user-service (plain JDK HTTP server) with the four internal endpoints status-service uses:
 *  GET  /internal/users/{viewerId}/contact-owners      who has the viewer as a contact (minus blocks either way)
 *  GET  /internal/contacts/relation?ownerId=&viewerId= contact? blocked?
 *  GET  /internal/users/{id}/privacy                    read receipts (404 for unknown users)
 *  POST /internal/users/summaries                       profiles (leaves out users who blocked the viewer)
 * It checks the X-Internal-Token header like the real one and can be switched "down" (HTTP 500).
 */
public class FakeUserService {

    public static final String INTERNAL_TOKEN = "test-internal-token-test-internal-1234";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final HttpServer server;
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    /** "owner>contact": contact is in owner's contact list. */
    private final Set<String> contacts = ConcurrentHashMap.newKeySet();
    /** "blocker>blocked" */
    private final Set<String> blocks = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Boolean> readReceipts = new ConcurrentHashMap<>();

    public volatile boolean down;

    public FakeUserService() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.createContext("/internal/users/summaries", this::handleSummaries);
        server.createContext("/internal/users/", this::handleUserPaths);
        server.createContext("/internal/contacts/relation", this::handleRelation);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // ---- what tests can set up ----

    public UUID addUser(String displayName) {
        UUID id = UUID.randomUUID();
        names.put(id, displayName);
        return id;
    }

    /** user-service no longer knows this user (no profile in summaries, privacy 404). Contacts stay. */
    public void forgetUser(UUID id) {
        names.remove(id);
    }

    /** owner puts contact into owner's contact list. */
    public void addContact(UUID owner, UUID contact) {
        contacts.add(owner + ">" + contact);
    }

    public void removeContact(UUID owner, UUID contact) {
        contacts.remove(owner + ">" + contact);
    }

    public void block(UUID blocker, UUID blocked) {
        blocks.add(blocker + ">" + blocked);
    }

    public void unblock(UUID blocker, UUID blocked) {
        blocks.remove(blocker + ">" + blocked);
    }

    public void setReadReceipts(UUID userId, boolean enabled) {
        readReceipts.put(userId, enabled);
    }

    private boolean blockedEitherWay(String a, String b) {
        return blocks.contains(a + ">" + b) || blocks.contains(b + ">" + a);
    }

    // ---- fake endpoints ----

    /** /internal/users/{id}/contact-owners and /internal/users/{id}/privacy */
    private void handleUserPaths(HttpExchange exchange) throws IOException {
        if (!allowed(exchange)) {
            return;
        }
        String[] parts = exchange.getRequestURI().getPath().split("/");   // "", internal, users, {id}, what
        UUID userId = UUID.fromString(parts[3]);
        String what = parts[4];
        if ("contact-owners".equals(what)) {
            List<String> owners = new ArrayList<>();
            for (String pair : contacts) {
                String[] ownerAndContact = pair.split(">");
                if (ownerAndContact[1].equals(userId.toString()) && !blockedEitherWay(ownerAndContact[0], userId.toString())) {
                    owners.add("\"" + ownerAndContact[0] + "\"");
                }
            }
            respond(exchange, 200, "{\"ownerIds\":[" + String.join(",", owners) + "]}");
        } else if ("privacy".equals(what)) {
            if (!names.containsKey(userId)) {
                respond(exchange, 404, "null");
                return;
            }
            boolean enabled = readReceipts.getOrDefault(userId, true);
            respond(exchange, 200, "{\"lastSeen\":\"EVERYONE\",\"profilePhoto\":\"EVERYONE\",\"about\":\"EVERYONE\","
                    + "\"groupAdd\":\"EVERYONE\",\"readReceipts\":" + enabled + "}");
        } else {
            respond(exchange, 404, "null");
        }
    }

    private void handleRelation(HttpExchange exchange) throws IOException {
        if (!allowed(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange);
        String owner = query.get("ownerId");
        String viewer = query.get("viewerId");
        boolean contact = contacts.contains(owner + ">" + viewer);
        respond(exchange, 200, "{\"contact\":" + contact + ",\"blocked\":" + blockedEitherWay(owner, viewer) + "}");
    }

    private void handleSummaries(HttpExchange exchange) throws IOException {
        if (!allowed(exchange)) {
            return;
        }
        JsonNode body = jsonMapper.readTree(exchange.getRequestBody().readAllBytes());
        String viewer = body.get("viewerId").asText();
        List<String> items = new ArrayList<>();
        for (JsonNode node : body.get("userIds")) {
            UUID id = UUID.fromString(node.asText());
            String name = names.get(id);
            if (name != null && !blocks.contains(id + ">" + viewer)) {
                items.add("{\"id\":\"" + id + "\",\"username\":\"" + name.toLowerCase().replace(' ', '_')
                        + "\",\"displayName\":\"" + name + "\",\"profileImageUrl\":null,\"about\":null}");
            }
        }
        respond(exchange, 200, "[" + String.join(",", items) + "]");
    }

    /** Checks the "down" switch and the internal token. Returns false when it already answered. */
    private boolean allowed(HttpExchange exchange) throws IOException {
        if (down) {
            respond(exchange, 500, "null");
            return false;
        }
        if (!INTERNAL_TOKEN.equals(exchange.getRequestHeaders().getFirst("X-Internal-Token"))) {
            respond(exchange, 401, "null");
            return false;
        }
        return true;
    }

    private static Map<String, String> query(HttpExchange exchange) {
        return java.util.Arrays.stream(exchange.getRequestURI().getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(p -> p[0], p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)));
    }

    private static void respond(HttpExchange exchange, int status, String dataJson) throws IOException {
        String json = "{\"success\":" + (status == 200) + ",\"message\":\"fake\",\"data\":" + dataJson
                + ",\"timestamp\":\"2026-11-04T10:00:00Z\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
