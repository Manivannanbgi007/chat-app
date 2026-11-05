package com.chatapp.gateway.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

/**
 * A tiny fake microservice (plain JDK HTTP server). It answers every request with 200 and a standard
 * envelope, and remembers which headers the gateway passed on.
 */
public class FakeBackend {

    private final HttpServer server;
    public final AtomicReference<String> lastCorrelationId = new AtomicReference<>();
    public final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    public final AtomicReference<String> lastPath = new AtomicReference<>();
    /** When true, the backend drops every connection without an answer (like a crashed service). */
    public volatile boolean broken;
    /** Every answer waits this long first (a slow service). 0 = answer at once. */
    public volatile long delayMillis;

    public FakeBackend() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        // several requests at the same time (the default executor of the JDK server handles them one by one)
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-backend");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/", exchange -> {
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (broken) {
                exchange.getRequestBody().readAllBytes();
                throw new java.io.IOException("simulated crash");   // the JDK server closes the connection
            }
            lastCorrelationId.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastPath.set(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();   // read the whole request body, like a real service (uploads)
            // real services echo the correlation id back too, which used to produce a duplicate header
            exchange.getResponseHeaders().add("X-Correlation-Id", String.valueOf(lastCorrelationId.get()));
            byte[] body = "{\"success\":true,\"message\":\"pong\",\"data\":null,\"timestamp\":\"2026-01-01T00:00:00Z\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
