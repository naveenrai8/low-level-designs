package com.lld.idgen;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * UUIDv7 generator following RFC 9562 section 5.7, with monotonicity by section 6.2 "Method 1" (a fixed-length counter in the rand_a field).
 *
 * <pre>
 *  bits: 48          | 4       | 12            | 2       | 62
 *        unix_ts_ms  | ver=0111| counter       | var=10  | random
 * </pre>
 *
 * <p>When the millisecond changes, the 12-bit counter restarts at a random value whose top bit is 0 (the RFC's roll-over guard, so there is room to count). In the same millisecond it adds 1. If it would pass 4095 it waits for the clock via {@link Waiter}. If the clock steps back by at most {@code maxBackwardsMillis} it keeps the last millisecond so the order never breaks; a bigger step throws {@link ClockMovedBackwardsException} (RFC 9562 section 6.2 "Monotonic Error Checking"). That bound is also the worst-case stall: without it a one-hour step back would make the generator spin for an hour while holding the lock.
 *
 * <p>Thread safety: one lock guards the (lastMillis, counter) pair. UUID work is cheap, so a lock is simpler than CAS here.
 */
public final class UuidV7Generator implements StringIdGenerator {

    private static final int MAX_COUNTER = 0xFFF;
    private static final long RAND_B_MASK = (1L << 62) - 1;

    private final Clock clock;
    private final Waiter waiter;
    private final RandomGenerator random;
    private final long maxBackwardsMillis;
    private long lastMillis = -1;
    private int counter;

    /** Creates a generator. Pass a seeded {@code Random} in tests for exact output; use {@code SecureRandom} in production. {@code maxBackwardsMillis} is how far the clock may step back before {@link #nextUuid()} throws. */
    public UuidV7Generator(Clock clock, Waiter waiter, RandomGenerator random, long maxBackwardsMillis) {
        if (maxBackwardsMillis < 0) {
            throw new IllegalArgumentException("maxBackwardsMillis must be >= 0");
        }
        this.maxBackwardsMillis = maxBackwardsMillis;
        this.clock = Objects.requireNonNull(clock);
        this.waiter = Objects.requireNonNull(waiter);
        this.random = Objects.requireNonNull(random);
    }

    /** Returns the next UUIDv7 as a lowercase 36-character string. */
    @Override
    public String next() {
        return nextUuid().toString();
    }

    /**
     * Returns the next UUIDv7 as a {@link UUID}, so callers can cross-check {@code version()} and {@code variant()}.
     *
     * @throws ClockMovedBackwardsException if the clock is further behind the last issued millisecond than the tolerance
     * @throws IllegalStateException if the clock is outside the 48-bit range
     */
    public synchronized UUID nextUuid() {
        while (true) {
            long now = clock.millis();
            if (now < 0 || now >= (1L << 48)) {
                throw new IllegalStateException("clock outside the 48-bit unix-ms range: " + now);
            }
            if (lastMillis - now > maxBackwardsMillis) {
                throw new ClockMovedBackwardsException(lastMillis - now, maxBackwardsMillis);
            }
            if (now > lastMillis) {
                lastMillis = now;
                counter = random.nextInt(MAX_COUNTER / 2 + 1); // top bit 0: roll-over guard
                break;
            }
            // same millisecond, or the clock stepped back: stay on lastMillis and count up
            if (counter < MAX_COUNTER) {
                counter++;
                break;
            }
            waiter.pause(); // counter exhausted: wait until the clock passes lastMillis
        }
        long randB = random.nextLong();
        return toUuid(lastMillis, counter, randB);
    }

    /** Packs the fields into a UUID: sets version 7 and variant 10 and keeps 62 bits of {@code randB}. Public so tests can check the RFC's example vector. */
    public static UUID toUuid(long unixMillis, int counter12, long randB) {
        long msb = (unixMillis << 16) | 0x7000L | (counter12 & 0xFFFL);
        long lsb = (0b10L << 62) | (randB & RAND_B_MASK);
        return new UUID(msb, lsb);
    }

    /** Reads the 48-bit unix-millisecond timestamp back out of a UUIDv7. */
    public static long unixMillis(UUID uuid) {
        return uuid.getMostSignificantBits() >>> 16;
    }
}
