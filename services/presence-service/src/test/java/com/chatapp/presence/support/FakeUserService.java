package com.chatapp.presence.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A fake user-service (plain JDK HTTP server) with ONE endpoint: POST /internal/users/presence-visibility.
 * Like the real one it needs the X-Internal-Token header. Tests choose who may NOT see whom, or switch it off.
 */
public class FakeUserService {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final HttpServer server;
    private final Set<String> hiddenPairs = new CopyOnWriteArraySet<>();

    public volatile boolean down;
    public final AtomicInteger calls = new AtomicInteger();

    public FakeUserService() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.createContext("/internal/users/presence-visibility", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** From now on the viewer may NOT see the target. */
    public void hide(UUID viewer, UUID target) {
        hiddenPairs.add(viewer + ">" + target);
    }

    public void reset() {
        hiddenPairs.clear();
        down = false;
    }

    private void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        if (down) {
            respond(exchange, 500, "{\"success\":false}");
            return;
        }
        if (!TestJwt.INTERNAL_TOKEN.equals(exchange.getRequestHeaders().getFirst("X-Internal-Token"))) {
            respond(exchange, 401, "{\"success\":false}");
            return;
        }
        JsonNode body = jsonMapper.readTree(exchange.getRequestBody());
        boolean visible = !hiddenPairs.contains(body.get("viewerId").asString() + ">" + body.get("targetId").asString());
        respond(exchange, 200, "{\"success\":true,\"message\":\"ok\",\"data\":{\"visible\":" + visible + "}}");
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
