package com.chatapp.message.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two fake microservices for tests (plain JDK HTTP servers):
 *  - conversation-service: GET /internal/conversations/{id} (private chats and groups, with membership periods)
 *  - user-service:         GET /internal/blocks?a=&b=   and   GET /internal/users/{id}/privacy
 * Both check the X-Internal-Token header like the real ones.
 */
public class FakePeers {

    public static final String INTERNAL_TOKEN = "test-only-internal-token-0123456789";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final HttpServer conversationServer = start();
    private final HttpServer userServer = start();
    private final HttpServer mediaServer = start();

    private final Map<UUID, FakeMedia> media = new ConcurrentHashMap<>();
    /** "requesterId:variant" of every download link the fake media-service signed. */
    public final List<String> downloadLinkRequests = new java.util.concurrent.CopyOnWriteArrayList<>();
    public volatile boolean mediaServiceDown;
    private final Map<UUID, FakeConversation> conversations = new ConcurrentHashMap<>();
    private final Set<String> blockedPairs = new CopyOnWriteArraySet<>();
    private final Map<UUID, Boolean> readReceipts = new ConcurrentHashMap<>();

    public volatile boolean conversationServiceDown;
    public volatile boolean userServiceDown;
    public final AtomicInteger conversationCalls = new AtomicInteger();

    /** One membership period, like conversation-service's membership_periods table. */
    private static final class Period {
        final UUID userId;
        final Instant joinedAt;
        Instant leftAt;

        Period(UUID userId, Instant joinedAt) {
            this.userId = userId;
            this.joinedAt = joinedAt;
        }
    }

    /** What conversation-service knows about one conversation. All methods are synchronized (server threads read). */
    private static final class FakeConversation {
        final UUID id;
        final String type;
        final List<Period> periods = new ArrayList<>();
        final Set<UUID> admins = new LinkedHashSet<>();
        boolean onlyAdminsCanSend;

        FakeConversation(UUID id, String type) {
            this.id = id;
            this.type = type;
        }

        synchronized Map<String, Object> toJson() {
            List<String> current = periods.stream().filter(p -> p.leftAt == null)
                    .map(p -> p.userId.toString()).distinct().toList();
            List<Map<String, Object>> members = new ArrayList<>();
            for (Period period : periods) {
                Map<String, Object> member = new LinkedHashMap<>();
                member.put("userId", period.userId.toString());
                member.put("joinedAt", period.joinedAt.toString());
                member.put("leftAt", period.leftAt == null ? null : period.leftAt.toString());
                members.add(member);
            }
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", id.toString());
            json.put("type", type);
            json.put("participantIds", current);
            json.put("adminIds", admins.stream().map(UUID::toString).toList());
            json.put("onlyAdminsCanSend", onlyAdminsCanSend);
            json.put("members", members);
            return json;
        }

        synchronized void join(UUID userId, Instant at) {
            periods.add(new Period(userId, at));
        }

        synchronized void leave(UUID userId, Instant at) {
            periods.stream().filter(p -> p.userId.equals(userId) && p.leftAt == null).forEach(p -> p.leftAt = at);
            admins.remove(userId);
        }
    }

    /** A file in the fake media-service. attachedReference is null until a message uses it. */
    private static final class FakeMedia {
        final UUID id;
        final UUID ownerId;
        final String kind;
        final String fileName;
        final String mimeType;
        final long sizeBytes;
        final Integer width;
        final Integer height;
        final Integer durationSeconds;
        final boolean hasThumbnail;
        String attachedReference;

        FakeMedia(UUID id, UUID ownerId, String kind, String fileName, String mimeType, long sizeBytes,
                  Integer width, Integer height, Integer durationSeconds, boolean hasThumbnail) {
            this.id = id;
            this.ownerId = ownerId;
            this.kind = kind;
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.sizeBytes = sizeBytes;
            this.width = width;
            this.height = height;
            this.durationSeconds = durationSeconds;
            this.hasThumbnail = hasThumbnail;
        }

        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", id.toString());
            json.put("kind", kind);
            json.put("fileName", fileName);
            json.put("mimeType", mimeType);
            json.put("sizeBytes", sizeBytes);
            json.put("width", width);
            json.put("height", height);
            json.put("durationSeconds", durationSeconds);
            json.put("hasThumbnail", hasThumbnail);
            return json;
        }
    }

    public FakePeers() {
        mediaServer.createContext("/internal/media/", exchange -> {
            if (!authorized(exchange) || mediaServiceDown) {
                respond(exchange, mediaServiceDown ? 500 : 401, "{\"success\":false}");
                return;
            }
            String[] parts = exchange.getRequestURI().getPath().split("/");     // "", internal, media, {id}, attach|download-link
            FakeMedia file = media.get(UUID.fromString(parts[3]));
            var body = jsonMapper.readTree(exchange.getRequestBody());
            if ("attach".equals(parts[4])) {
                String reference = body.get("reference").asString();
                if (file == null || !file.ownerId.toString().equals(body.get("ownerId").asString())) {
                    respond(exchange, 404, "{\"success\":false,\"data\":null}");
                } else if (!file.kind.equals(body.get("kind").asString())) {
                    respond(exchange, 400, "{\"success\":false,\"data\":null}");
                } else {
                    synchronized (file) {
                        if (file.attachedReference != null && !file.attachedReference.equals(reference)) {
                            respond(exchange, 409, "{\"success\":false,\"data\":null}");
                            return;
                        }
                        file.attachedReference = reference;
                    }
                    respond(exchange, 200, jsonMapper.writeValueAsString(
                            Map.of("success", true, "message", "ok", "data", file.toJson())));
                }
                return;
            }
            // download-link
            String variant = body.get("variant").asString();
            if (file == null || file.attachedReference == null || ("thumbnail".equals(variant) && !file.hasThumbnail)) {
                respond(exchange, 404, "{\"success\":false,\"data\":null}");
                return;
            }
            downloadLinkRequests.add(body.get("requesterId").asString() + ":" + variant);
            respond(exchange, 200, jsonMapper.writeValueAsString(Map.of("success", true, "message", "ok", "data", Map.of(
                    "url", "http://storage.test/" + file.id + "/" + variant + "?signature=fake",
                    "expiresAt", Instant.now().plusSeconds(300).toString()))));
        });

        conversationServer.createContext("/internal/conversations/", exchange -> {
            conversationCalls.incrementAndGet();
            if (!authorized(exchange) || conversationServiceDown) {
                respond(exchange, conversationServiceDown ? 500 : 401, "{\"success\":false}");
                return;
            }
            String id = exchange.getRequestURI().getPath().substring("/internal/conversations/".length());
            FakeConversation conversation = conversations.get(UUID.fromString(id));
            if (conversation == null) {
                respond(exchange, 404, "{\"success\":false,\"message\":\"not found\",\"data\":null}");
                return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "ok");
            body.put("data", conversation.toJson());
            respond(exchange, 200, jsonMapper.writeValueAsString(body));
        });

        userServer.createContext("/internal/blocks", exchange -> {
            if (!authorized(exchange) || userServiceDown) {
                respond(exchange, userServiceDown ? 500 : 401, "{\"success\":false}");
                return;
            }
            Map<String, String> query = query(exchange);
            boolean blocked = blockedPairs.contains(query.get("a") + ">" + query.get("b"))
                    || blockedPairs.contains(query.get("b") + ">" + query.get("a"));
            respond(exchange, 200, "{\"success\":true,\"message\":\"ok\",\"data\":{\"blocked\":" + blocked + "}}");
        });

        userServer.createContext("/internal/users/", exchange -> {
            if (!authorized(exchange) || userServiceDown) {
                respond(exchange, userServiceDown ? 500 : 401, "{\"success\":false}");
                return;
            }
            String path = exchange.getRequestURI().getPath();       // /internal/users/{id}/privacy
            UUID userId = UUID.fromString(path.split("/")[3]);
            boolean enabled = readReceipts.getOrDefault(userId, true);
            respond(exchange, 200, "{\"success\":true,\"message\":\"ok\",\"data\":{\"lastSeen\":\"EVERYONE\","
                    + "\"profilePhoto\":\"EVERYONE\",\"about\":\"EVERYONE\",\"groupAdd\":\"EVERYONE\",\"readReceipts\":" + enabled + "}}");
        });
    }

    public String conversationUrl() {
        return "http://127.0.0.1:" + conversationServer.getAddress().getPort();
    }

    public String userUrl() {
        return "http://127.0.0.1:" + userServer.getAddress().getPort();
    }

    public String mediaUrl() {
        return "http://127.0.0.1:" + mediaServer.getAddress().getPort();
    }

    // ------------------------------------------------------------ set-up helpers

    /** A file uploaded by ownerId (not used by any message yet). Returns its id. */
    public UUID addMedia(UUID ownerId, String kind, String fileName, String mimeType, long sizeBytes,
                         Integer width, Integer height, Integer durationSeconds, boolean hasThumbnail) {
        UUID id = UUID.randomUUID();
        media.put(id, new FakeMedia(id, ownerId, kind, fileName, mimeType, sizeBytes, width, height, durationSeconds, hasThumbnail));
        return id;
    }

    /** A private chat. Everybody joined a minute ago, so every message sent by a test is inside their period. */
    public void addConversation(UUID id, UUID... participants) {
        FakeConversation conversation = new FakeConversation(id, "PRIVATE");
        Instant joinedAt = now().minusSeconds(60);
        for (UUID participant : participants) {
            conversation.join(participant, joinedAt);
        }
        conversations.put(id, conversation);
    }

    /** A group created a minute ago; the first user is its admin. */
    public void addGroup(UUID id, UUID admin, UUID... members) {
        addGroupAt(id, now().minusSeconds(60), admin, members);
    }

    /** A group created at the given time (the same time a test puts into a ConversationCreatedEvent). */
    public void addGroupAt(UUID id, Instant joinedAt, UUID admin, UUID... members) {
        FakeConversation conversation = new FakeConversation(id, "GROUP");
        conversation.join(admin, joinedAt);
        for (UUID member : members) {
            conversation.join(member, joinedAt);
        }
        conversation.admins.add(admin);
        conversations.put(id, conversation);
    }

    /**
     * Adds a member NOW (a new period). Short pauses before and after make sure no message shares the exact
     * microsecond with the join, so "sent before" and "sent after" are always clear in tests.
     * Returns the join time.
     */
    public Instant addMember(UUID conversationId, UUID userId) {
        pause();
        Instant at = now();
        conversations.get(conversationId).join(userId, at);
        pause();
        return at;
    }

    /** Ends the member's period NOW (removed or left). Returns the leave time. */
    public Instant removeMember(UUID conversationId, UUID userId) {
        pause();
        Instant at = now();
        conversations.get(conversationId).leave(userId, at);
        pause();
        return at;
    }

    public void setAdmins(UUID conversationId, UUID... admins) {
        FakeConversation conversation = conversations.get(conversationId);
        synchronized (conversation) {
            conversation.admins.clear();
            conversation.admins.addAll(List.of(admins));
        }
    }

    public void setOnlyAdminsCanSend(UUID conversationId, boolean value) {
        FakeConversation conversation = conversations.get(conversationId);
        synchronized (conversation) {
            conversation.onlyAdminsCanSend = value;
        }
    }

    public void block(UUID blocker, UUID blocked) {
        blockedPairs.add(blocker + ">" + blocked);
    }

    public void unblockAll() {
        blockedPairs.clear();
    }

    public void setReadReceipts(UUID userId, boolean enabled) {
        readReceipts.put(userId, enabled);
    }

    // ------------------------------------------------------------------ plumbing

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static void pause() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean authorized(HttpExchange exchange) {
        return INTERNAL_TOKEN.equals(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
    }

    private static Map<String, String> query(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        return java.util.Arrays.stream(raw.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(p -> p[0], p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)));
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
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
