package com.lld.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * <b>Bug.</b> Measures a timeout with wall-clock time. If the wall clock steps back an hour, a
 * 10-second timeout lasts an hour and 10 seconds. If it steps forward, the timeout fires
 * immediately. Elapsed time computed this way can even come out negative.
 *
 * @see MonotonicDeadline the fix
 */
public final class WallClockDeadline implements Deadline {

    private final Clock clock;
    private final Instant expiresAt;

    public WallClockDeadline(Clock clock, Duration timeout) {
        this.clock = clock;
        this.expiresAt = clock.instant().plus(timeout);
    }

    @Override
    public boolean hasExpired() {
        return !clock.instant().isBefore(expiresAt);
    }

    @Override
    public Duration remaining() {
        Duration left = Duration.between(clock.instant(), expiresAt);
        return left.isNegative() ? Duration.ZERO : left;
    }
}
