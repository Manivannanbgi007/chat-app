package com.chatapp.notification.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.chatapp.notification.push.PushSender;

/**
 * The PushSender used in tests (it replaces LoggingPushSender): it remembers what it was asked to send and can be
 * told to fail, like a push provider that is down.
 */
public class TestPushSender implements PushSender {

    public final List<PushMessage> sent = new CopyOnWriteArrayList<>();
    public volatile boolean failing;

    @Override
    public void send(PushMessage message) {
        if (failing) {
            throw new IllegalStateException("push provider is down (with a secret token in the text: tok-123456)");
        }
        sent.add(message);
    }

    public void reset() {
        sent.clear();
        failing = false;
    }
}
