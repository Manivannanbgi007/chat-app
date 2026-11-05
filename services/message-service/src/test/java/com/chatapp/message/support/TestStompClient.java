package com.chatapp.message.support;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.AbstractMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.util.MimeType;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * A small real STOMP client for tests. It behaves like the browser: connects with a token in the CONNECT frame,
 * subscribes, sends, and collects everything it receives (as JSON text) in queues per destination.
 */
public class TestStompClient implements AutoCloseable {

    private final WebSocketStompClient stompClient;
    private StompSession session;
    private final CompletableFuture<Void> connected = new CompletableFuture<>();
    private final BlockingQueue<String> errors = new LinkedBlockingQueue<>();
    private volatile boolean closed;

    private TestStompClient() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new RawBytesConverter());
        stompClient.setInboundMessageSizeLimit(64 * 1024);
    }

    /** Opens the socket and sends CONNECT. Throws if the server refuses (error frame / closed socket / timeout). */
    public static TestStompClient connect(int port, String bearerToken) throws Exception {
        TestStompClient client = new TestStompClient();
        StompHeaders connectHeaders = new StompHeaders();
        if (bearerToken != null) {
            connectHeaders.add("Authorization", "Bearer " + bearerToken);
        }
        client.session = client.stompClient.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(),
                connectHeaders, client.new Handler()).get(10, TimeUnit.SECONDS);
        client.connected.get(10, TimeUnit.SECONDS);
        return client;
    }

    /** Subscribes and returns a queue of the JSON bodies that arrive on that destination. */
    public BlockingQueue<String> subscribe(String destination) {
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
        });
        return queue;
    }

    public void send(String destination, String jsonBody) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination(destination);
        headers.setContentType(org.springframework.util.MimeTypeUtils.APPLICATION_JSON);
        session.send(headers, jsonBody.getBytes(StandardCharsets.UTF_8));
    }

    public boolean isConnected() {
        return session != null && session.isConnected() && !closed;
    }

    /** Waits until the server has closed this connection. */
    public boolean waitUntilClosed(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!isConnected()) {
                return true;
            }
            Thread.sleep(50);
        }
        return !isConnected();
    }

    /** ERROR frames (and transport errors) received from the server. */
    public BlockingQueue<String> errors() {
        return errors;
    }

    @Override
    public void close() {
        closed = true;
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
        stompClient.stop();
    }

    /** Helper: the next JSON text on a queue or fail after the timeout. */
    public static String next(BlockingQueue<String> queue, long timeoutMs) throws Exception {
        String value = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (value == null) {
            throw new TimeoutException("nothing received within " + timeoutMs + " ms");
        }
        return value;
    }

    public static List<String> drain(BlockingQueue<String> queue) {
        List<String> all = new java.util.ArrayList<>();
        queue.drainTo(all);
        return all;
    }

    /** Passes the body through as bytes whatever its content type is (the server mixes text/plain and JSON). */
    private static class RawBytesConverter extends AbstractMessageConverter {
        RawBytesConverter() {
            super(new MimeType("*", "*"));
        }

        @Override
        protected boolean supports(Class<?> clazz) {
            return byte[].class == clazz;
        }

        @Override
        protected Object convertFromInternal(Message<?> message, Class<?> targetClass, Object conversionHint) {
            return message.getPayload();
        }

        @Override
        protected Object convertToInternal(Object payload, MessageHeaders headers, Object conversionHint) {
            return payload;
        }
    }

    private class Handler extends StompSessionHandlerAdapter {
        @Override
        public void afterConnected(StompSession connectedSession, StompHeaders connectedHeaders) {
            connected.complete(null);
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            // ERROR frames arrive here
            errors.add(String.valueOf(headers.getFirst("message")));
            connected.completeExceptionally(new IllegalStateException("STOMP error: " + headers.getFirst("message")));
        }

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return byte[].class;
        }

        @Override
        public void handleException(StompSession s, StompCommand command, StompHeaders headers, byte[] payload, Throwable exception) {
            errors.add(String.valueOf(exception.getMessage()));
        }

        @Override
        public void handleTransportError(StompSession s, Throwable exception) {
            closed = true;
            errors.add(String.valueOf(exception.getMessage()));
            connected.completeExceptionally(exception);
        }
    }
}
