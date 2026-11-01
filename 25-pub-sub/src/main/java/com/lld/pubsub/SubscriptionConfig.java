package com.lld.pubsub;

/**
 * Settings for one subscriber group.
 *
 * @param maxAttempts     total deliveries allowed per message (first try included) before it goes to the DLQ
 * @param ackTimeoutNanos how long a consumer has to ack before the message counts as failed and is redelivered
 * @param startAt         where a new group starts reading
 */
public record SubscriptionConfig(int maxAttempts, long ackTimeoutNanos, StartAt startAt) {

    /** Rejects settings that would make a message undeliverable or instantly expired. */
    public SubscriptionConfig {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (ackTimeoutNanos < 1) {
            throw new IllegalArgumentException("ackTimeoutNanos must be >= 1");
        }
        java.util.Objects.requireNonNull(startAt, "startAt");
    }

    /** Returns a sensible default: 3 attempts, 30 second ack deadline, start from the earliest message. */
    public static SubscriptionConfig defaults() {
        return new SubscriptionConfig(3, 30_000_000_000L, StartAt.EARLIEST);
    }
}
