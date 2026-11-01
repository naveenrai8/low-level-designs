package com.lld.pubsub;

import java.util.Objects;

/**
 * One published message: its position in the topic log, its ordering key and its payload.
 *
 * @param offset  the message's position in the topic log, starting at 0 and never reused
 * @param key     messages with the same key are handed to consumers in publish order
 * @param payload the body, opaque to the broker
 */
public record Message(long offset, String key, String payload) {

    /** Checks that key and payload are present, because a null key would silently break ordering. */
    public Message {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(payload, "payload");
    }
}
