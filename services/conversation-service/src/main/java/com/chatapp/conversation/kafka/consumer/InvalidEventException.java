package com.chatapp.conversation.kafka.consumer;

/**
 * The event can never be processed (broken JSON, missing field). Retrying is pointless,
 * so KafkaConfig sends it straight to the dead-letter topic.
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
