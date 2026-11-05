package com.chatapp.message.websocket;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Very small in-memory rate limiter for one WebSocket connection: "at most N events in the last W milliseconds".
 * Per instance and per connection, which is exactly what we want here (a connection lives on one instance).
 */
@Component
public class SlidingWindowLimiter {

    private static final long IDLE_EVICTION_MS = 60_000;

    private final Map<String, Deque<Long>> events = new ConcurrentHashMap<>();

    /** Returns true (and counts the event) if the caller is still below the limit. */
    public boolean allow(String key, int maxEvents, long windowMs) {
        long now = System.currentTimeMillis();
        Deque<Long> times = events.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() >= windowMs) {
                times.pollFirst();
            }
            if (times.size() >= maxEvents) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }

    /** Forget keys that have been quiet for a while, so the map cannot grow forever. */
    public void evictIdle() {
        long now = System.currentTimeMillis();
        events.entrySet().removeIf(entry -> {
            Deque<Long> times = entry.getValue();
            synchronized (times) {
                return times.isEmpty() || now - times.peekLast() > IDLE_EVICTION_MS;
            }
        });
    }
}
