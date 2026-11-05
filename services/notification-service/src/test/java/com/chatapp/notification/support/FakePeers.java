package com.chatapp.notification.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Three fake microservices (plain JDK HTTP servers) that notification-service calls:
 *  - presence-service:     GET  /internal/presence/online?userIds=a,b
 *  - conversation-service: GET  /internal/conversations/{id}   and   /internal/conversations/{id}/muted-users
 *  - user-service:         POST /internal/users/summaries
 * They check the X-Internal-Token header like the real ones, and can be switched "down" (HTTP 500).
 */
public class FakePeers {

    public static final String INTERNAL_TOKEN = "test-internal-token-test-internal-1234";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final HttpServer presenceServer = start();
    private final HttpServer conversationServer = start();
    private final HttpServer userServer = start();

    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Map<String, String>> conversations = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> muted = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    public volatile boolean presenceDown;
    public volatile boolean conversationDown;
    public volatile boolean userDown;
    public final AtomicInteger userCalls = new AtomicInteger();
    public final AtomicInteger presenceCalls = new AtomicInteger();
    /** The ids asked in each presence call (to check that one event costs one call). */
    public final List<String> presenceQueries = new CopyOnWriteArrayList<>();

    public FakePeers() {
        presenceServer.createContext("/internal/presence/online", exchange -> {
            presenceCalls.incrementAndGet();
            if (!authorized(exchange) || presenceDown) {
                respond(exchange, presenceDown ? 500 : 401, "{\"success\":false}");
                return;
            }
            String raw = query(exchange).getOrDefault("userIds", "");
            presenceQueries.add(raw);
            List<String> answer = new ArrayList<>();
            for (String id : raw.split(",")) {
                if (!id.isBlank() && online.contains(UUID.fromString(id.trim()))) {
                    answer.add(id.trim());
                }
            }
            respond(exchange, 200, envelope(Map.of("onlineUserIds", answer)));
        });

        conversationServer.createContext("/internal/conversations/", exchange -> {
            if (!authorized(exchange) || conversationDown) {
                respond(exchange, conversationDown ? 500 : 401, "{\"success\":false}");
                return;
            }
            String[] parts = exchange.getRequestURI().getPath().split("/");   // "", internal, conversations, {id}[, muted-users]
            UUID id = UUID.fromString(parts[3]);
            Map<String, String> conversation = conversations.get(id);
            if (conversation == null) {
                respond(exchange, 404, "{\"success\":false,\"message\":\"not found\",\"data\":null}");
                return;
            }
            if (parts.length > 4 && "muted-users".equals(parts[4])) {
                List<String> mutedIds = muted.getOrDefault(id, Set.of()).stream().map(UUID::toString).toList();
                respond(exchange, 200, envelope(Map.of("mutedUserIds", mutedIds)));
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", id.toString());
            data.put("type", conversation.get("type"));
            data.put("name", conversation.get("name"));
            data.put("participantIds", List.of());
            respond(exchange, 200, envelope(data));
        });

        userServer.createContext("/internal/users/summaries", exchange -> {
            userCalls.incrementAndGet();
            if (!authorized(exchange) || userDown) {
                respond(exchange, userDown ? 500 : 401, "{\"success\":false}");
                return;
            }
            JsonNode body = jsonMapper.readTree(exchange.getRequestBody());
            List<Map<String, Object>> profiles = new ArrayList<>();
            for (JsonNode idNode : body.get("userIds")) {
                UUID id = UUID.fromString(idNode.asString());
                if (names.containsKey(id)) {
                    Map<String, Object> profile = new LinkedHashMap<>();
                    profile.put("id", id.toString());
                    profile.put("username", "user");
                    profile.put("displayName", names.get(id));
                    profile.put("profileImageUrl", null);
                    profile.put("about", null);
                    profiles.add(profile);
                }
            }
            respond(exchange, 200, envelope(profiles));
        });
    }

    public String presenceUrl() {
        return "http://127.0.0.1:" + presenceServer.getAddress().getPort();
    }

    public String conversationUrl() {
        return "http://127.0.0.1:" + conversationServer.getAddress().getPort();
    }

    public String userUrl() {
        return "http://127.0.0.1:" + userServer.getAddress().getPort();
    }

    // ------------------------------------------------------------ set-up helpers

    public void setOnline(UUID... users) {
        online.addAll(List.of(users));
    }

    public void setOffline(UUID... users) {
        online.removeAll(List.of(users));
    }

    public void addPrivateChat(UUID conversationId) {
        Map<String, String> conversation = new LinkedHashMap<>();
        conversation.put("type", "PRIVATE");
        conversation.put("name", null);
        conversations.put(conversationId, conversation);
    }

    public void addGroup(UUID conversationId, String name) {
        Map<String, String> conversation = new LinkedHashMap<>();
        conversation.put("type", "GROUP");
        conversation.put("name", name);
        conversations.put(conversationId, conversation);
    }

    public void mute(UUID conversationId, UUID user) {
        muted.computeIfAbsent(conversationId, id -> ConcurrentHashMap.newKeySet()).add(user);
    }

    public void setName(UUID user, String displayName) {
        names.put(user, displayName);
    }

    // ------------------------------------------------------------------ plumbing

    private String envelope(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "ok");
        body.put("data", data);
        return jsonMapper.writeValueAsString(body);
    }

    private static boolean authorized(HttpExchange exchange) {
        return INTERNAL_TOKEN.equals(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
    }

    private static Map<String, String> query(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null) {
            return result;
        }
        for (String pair : raw.split("&")) {
            String[] keyValue = pair.split("=", 2);
            result.put(keyValue[0], keyValue.length > 1 ? URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newFixedThreadPool(8));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
