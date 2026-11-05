package com.chatapp.status.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A tiny fake media-service with the three internal endpoints status-service uses, following the real rules:
 *  POST /internal/media/{id}/attach         owner must match (404), kind must match (400), another reference (409)
 *  POST /internal/media/{id}/download-link  only attached files; thumbnail only when there is one (404 otherwise)
 *  POST /internal/media/{id}/release        only with the same owner AND reference (404 otherwise); repeat is fine
 * Tests upload "files" with addUpload(...) and can make release fail (releaseDown) to test the retries.
 */
public class FakeMediaService {

    public static final String INTERNAL_TOKEN = FakeUserService.INTERNAL_TOKEN;

    /** What the fake remembers about one uploaded file. */
    public static final class File {
        final UUID ownerId;
        final String kind;
        final boolean hasThumbnail;
        volatile String reference;      // null until attached
        volatile boolean deleted;

        File(UUID ownerId, String kind, boolean hasThumbnail) {
            this.ownerId = ownerId;
            this.kind = kind;
            this.hasThumbnail = hasThumbnail;
        }
    }

    /** One release request as it arrived. */
    public record ReleaseCall(UUID mediaId, String ownerId, String reference, int status) {
    }

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final HttpServer server;
    private final Map<UUID, File> files = new ConcurrentHashMap<>();
    private final List<ReleaseCall> releaseCalls = new CopyOnWriteArrayList<>();

    public volatile boolean down;
    public volatile boolean releaseDown;

    public FakeMediaService() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.createContext("/internal/media/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // ---- what tests can set up / look at ----

    public UUID addUpload(UUID ownerId, String kind, boolean hasThumbnail) {
        UUID id = UUID.randomUUID();
        files.put(id, new File(ownerId, kind, hasThumbnail));
        return id;
    }

    public boolean isDeleted(UUID mediaId) {
        File file = files.get(mediaId);
        return file != null && file.deleted;
    }

    public String referenceOf(UUID mediaId) {
        File file = files.get(mediaId);
        return file == null ? null : file.reference;
    }

    public List<ReleaseCall> releaseCalls(UUID mediaId) {
        return releaseCalls.stream().filter(call -> call.mediaId().equals(mediaId)).toList();
    }

    // ---- fake endpoints ----

    private void handle(HttpExchange exchange) throws IOException {
        if (down) {
            respond(exchange, 500, "null");
            return;
        }
        if (!INTERNAL_TOKEN.equals(exchange.getRequestHeaders().getFirst("X-Internal-Token"))) {
            respond(exchange, 401, "null");
            return;
        }
        String[] parts = exchange.getRequestURI().getPath().split("/");   // "", internal, media, {id}, action
        UUID mediaId = UUID.fromString(parts[3]);
        String action = parts[4];
        JsonNode body = jsonMapper.readTree(exchange.getRequestBody().readAllBytes());
        File file = files.get(mediaId);
        switch (action) {
            case "attach" -> attach(exchange, mediaId, file, body);
            case "download-link" -> downloadLink(exchange, mediaId, file, body);
            case "release" -> release(exchange, mediaId, file, body);
            default -> respond(exchange, 404, "null");
        }
    }

    private void attach(HttpExchange exchange, UUID mediaId, File file, JsonNode body) throws IOException {
        if (file == null || file.deleted || !file.ownerId.toString().equals(body.get("ownerId").asText())) {
            respond(exchange, 404, "null");
            return;
        }
        if (!file.kind.equals(body.get("kind").asText())) {
            respond(exchange, 400, "null");
            return;
        }
        String reference = body.get("reference").asText();
        synchronized (file) {
            if (file.reference != null && !file.reference.equals(reference)) {
                respond(exchange, 409, "null");
                return;
            }
            file.reference = reference;
        }
        boolean video = "VIDEO".equals(file.kind);
        respond(exchange, 200, "{\"id\":\"" + mediaId + "\",\"kind\":\"" + file.kind + "\",\"fileName\":\"f\","
                + "\"mimeType\":\"" + (video ? "video/mp4" : "image/jpeg") + "\",\"sizeBytes\":1234,"
                + "\"width\":640,\"height\":480,\"durationSeconds\":" + (video ? "12" : "null") + ","
                + "\"hasThumbnail\":" + file.hasThumbnail + ",\"createdAt\":\"2026-11-04T10:00:00Z\"}");
    }

    private void downloadLink(HttpExchange exchange, UUID mediaId, File file, JsonNode body) throws IOException {
        boolean thumbnail = "thumbnail".equals(body.get("variant").asText());
        if (file == null || file.deleted || file.reference == null || (thumbnail && !file.hasThumbnail)) {
            respond(exchange, 404, "null");
            return;
        }
        String requester = body.get("requesterId").asText();
        respond(exchange, 200, "{\"url\":\"http://storage.test/" + mediaId + "/" + body.get("variant").asText()
                + "?for=" + requester + "\",\"expiresAt\":\"2030-01-01T00:00:00Z\"}");
    }

    private void release(HttpExchange exchange, UUID mediaId, File file, JsonNode body) throws IOException {
        String ownerId = body.get("ownerId").asText();
        String reference = body.get("reference").asText();
        int status;
        if (releaseDown) {
            status = 503;
        } else if (file != null && file.deleted) {
            status = 200;     // already gone: fine
        } else if (file == null || !file.ownerId.toString().equals(ownerId) || !reference.equals(file.reference)) {
            status = 404;
        } else {
            file.deleted = true;
            status = 200;
        }
        releaseCalls.add(new ReleaseCall(mediaId, ownerId, reference, status));
        respond(exchange, status, "null");
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
