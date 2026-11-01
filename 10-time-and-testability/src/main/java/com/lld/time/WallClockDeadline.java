package com.lld.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * <b>Bug.</b> Measures a timeout with wall-clock time.
 * <p>Example: a 10-second timeout starts, then the wall clock steps back one hour. The timeout now lasts an hour and 10 seconds. If the clock steps forward instead, the timeout fires at once.
 * 
 * @see MonotonicDeadline the fix
 */
public final class WallClockDeadline implements Deadline {

    private final Clock clock;
    private final Instant expiresAt;

    /**
     * Fixes the expiry as "clock now plus timeout" on the wall clock.
     */
    public WallClockDeadline(Clock clock, Duration timeout) {
        this.clock = clock;
        this.expiresAt = clock.instant().plus(timeout);
    }

    /**
     * True once the wall clock reaches the expiry. Wrong if the wall clock has been stepped.
     */
    @Override
    public boolean hasExpired() {
        return !clock.instant().isBefore(expiresAt);
    }

    /**
     * Returns the wall-clock time left, never below zero.
     */
    @Override
    public Duration remaining() {
        Duration left = Duration.between(clock.instant(), expiresAt);
        return left.isNegative() ? Duration.ZERO : left;
    }
}
