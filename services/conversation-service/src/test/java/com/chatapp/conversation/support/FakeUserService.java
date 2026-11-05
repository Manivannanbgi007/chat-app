package com.chatapp.conversation.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A tiny fake user-service (plain JDK HTTP server) for tests.
 * It knows the users and blocks that a test registers, requires the X-Internal-Token like the real one,
 * and can be switched to "broken" to test how conversation-service reacts when user-service is down.
 */
public class FakeUserService {

    public static final String TOKEN = "test-internal-token-test-internal-token";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final HttpServer server;
    private final Map<UUID, String> users = new ConcurrentHashMap<>();
    private final Set<String> blockedPairs = ConcurrentHashMap.newKeySet();
    private final Set<UUID> refusesGroupAdd = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean failing = new AtomicBoolean(false);
    private final AtomicInteger summaryCalls = new AtomicInteger();
    private final List<String> receivedTokens = new CopyOnWriteArrayList<>();

    public FakeUserService() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/internal/users/summaries", this::handleSummaries);
        server.createContext("/internal/users/group-add-check", this::handleGroupAddCheck);
        server.createContext("/internal/blocks", this::handleBlocks);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // ---- what tests can set up ----

    public UUID addUser(String displayName) {
        UUID id = UUID.randomUUID();
        users.put(id, displayName);
        return id;
    }

    /** Pretend that a blocked b (the real service answers true for either direction). */
    public void block(UUID a, UUID b) {
        blockedPairs.add(a + "|" + b);
    }

    /** Pretend that this user's privacy setting ("who can add me to groups") refuses everybody. */
    public void refuseGroupAdd(UUID userId) {
        refusesGroupAdd.add(userId);
    }

    public void setFailing(boolean failing) {
        this.failing.set(failing);
    }

    public int summaryCalls() {
        return summaryCalls.get();
    }

    public List<String> receivedTokens() {
        return receivedTokens;
    }

    // ---- fake endpoints ----

    private void handleSummaries(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        summaryCalls.incrementAndGet();
        JsonNode body = jsonMapper.readTree(exchange.getRequestBody().readAllBytes());
        String items = "";
        for (JsonNode idNode : body.get("userIds")) {
            UUID id = UUID.fromString(idNode.asText());
            String name = users.get(id);
            if (name != null) {
                items += (items.isEmpty() ? "" : ",")
                        + "{\"id\":\"" + id + "\",\"username\":\"" + name.toLowerCase().replace(' ', '_')
                        + "\",\"displayName\":\"" + name + "\",\"profileImageUrl\":null,\"about\":null}";
            }
        }
        respond(exchange, 200, "[" + items + "]");
    }

    /** Like the real one: unknown users, blocks in either direction and refusing privacy are "not allowed". */
    private void handleGroupAddCheck(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        JsonNode body = jsonMapper.readTree(exchange.getRequestBody().readAllBytes());
        UUID actor = UUID.fromString(body.get("actorId").asText());
        String refused = "";
        for (JsonNode idNode : body.get("userIds")) {
            UUID id = UUID.fromString(idNode.asText());
            boolean blocked = blockedPairs.contains(actor + "|" + id) || blockedPairs.contains(id + "|" + actor);
            if (!id.equals(actor) && (!users.containsKey(id) || blocked || refusesGroupAdd.contains(id))) {
                refused += (refused.isEmpty() ? "" : ",") + "\"" + id + "\"";
            }
        }
        respond(exchange, 200, "{\"notAllowed\":[" + refused + "]}");
    }

    private void handleBlocks(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        Map<String, String> query = java.util.Arrays.stream(exchange.getRequestURI().getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(p -> p[0], p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)));
        String a = query.get("a");
        String b = query.get("b");
        boolean blocked = blockedPairs.contains(a + "|" + b) || blockedPairs.contains(b + "|" + a);
        respond(exchange, 200, "{\"blocked\":" + blocked + "}");
    }

    /** Checks the internal token and the "broken" switch. Returns false when it already answered. */
    private boolean authorized(HttpExchange exchange) throws IOException {
        String token = exchange.getRequestHeaders().getFirst("X-Internal-Token");
        receivedTokens.add(String.valueOf(token));
        if (failing.get()) {
            respond(exchange, 500, "null");
            return false;
        }
        if (!TOKEN.equals(token)) {
            respond(exchange, 401, "null");
            return false;
        }
        return true;
    }

    private void respond(HttpExchange exchange, int status, String dataJson) throws IOException {
        String json = "{\"success\":" + (status == 200) + ",\"message\":\"fake\",\"data\":" + dataJson
                + ",\"timestamp\":\"2026-11-04T10:00:00Z\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
