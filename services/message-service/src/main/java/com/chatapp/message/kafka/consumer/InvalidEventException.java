package com.chatapp.message.kafka.consumer;

/**
 * The event can never be processed (broken JSON, missing or malformed field). Retrying is pointless,
 * so KafkaConsumerConfig sends it straight to the dead-letter topic.
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
