package com.lld.booking;

import java.time.Duration;

/**
 * Time settings of the service.
 *
 * @param holdTtl how long a hold lasts
 * @param idempotencyTtl how long an idempotency key is remembered after a successful confirm (this bounds the store's memory)
 * @param expiredHoldRetention how long after expiry a hold id still answers "expired" (410) instead of "not found" (404)
 */
public record BookingConfig(Duration holdTtl, Duration idempotencyTtl, Duration expiredHoldRetention) {

    /** Rejects zero or negative durations, which would make every hold or key expire at once. */
    public BookingConfig {
        for (Duration d : new Duration[] {holdTtl, idempotencyTtl, expiredHoldRetention}) {
            if (d == null || d.isZero() || d.isNegative()) {
                throw new IllegalArgumentException("durations must be positive: " + d);
            }
        }
    }

    /** Returns the defaults: 5 minute holds, keys kept 24 hours, expired holds remembered 1 hour. */
    public static BookingConfig defaults() {
        return new BookingConfig(Duration.ofMinutes(5), Duration.ofHours(24), Duration.ofHours(1));
    }
}
