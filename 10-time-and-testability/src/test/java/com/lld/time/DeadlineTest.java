package com.lld.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * One scenario, two time sources. The wall clock is stepped (an NTP correction) while real elapsed time, the ticker, moves normally.
 */
class DeadlineTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final MutableClock wall = new MutableClock(Instant.parse("2026-03-08T09:00:00Z"));
    private final ManualTicker ticker = new ManualTicker();

    /**
     * Lets real time pass: moves both the wall clock and the ticker by the same amount.
     */
    private void elapse(Duration real) {
        wall.advance(real);
        ticker.advance(real);
    }

    /**
     * Proves that a wall-clock timeout is still waiting after 32 real seconds when the clock was stepped back an hour.
     */
    @Test
    void bugWallClockDeadlineNeverFiresWhenTheClockStepsBack() {
        Deadline deadline = new WallClockDeadline(wall, TIMEOUT);
        elapse(Duration.ofSeconds(2));
        wall.advance(Duration.ofHours(-1));            // NTP steps the clock back an hour
        elapse(Duration.ofSeconds(30));                // 32 s really elapsed, timeout was 10 s

        assertFalse(deadline.hasExpired(), "bug: a 10 s timeout is still waiting after 32 s");
        assertTrue(deadline.remaining().compareTo(Duration.ofMinutes(59)) > 0);
    }

    /**
     * Proves that a wall-clock timeout of 10 seconds fires after 1 millisecond when the clock was stepped forward.
     */
    @Test
    void bugWallClockDeadlineFiresAtOnceWhenTheClockStepsForward() {
        Deadline deadline = new WallClockDeadline(wall, TIMEOUT);
        wall.advance(Duration.ofMinutes(5));           // clock corrected forward
        elapse(Duration.ofMillis(1));

        assertTrue(deadline.hasExpired(), "bug: a 10 s timeout fired after 1 ms");
    }

    /**
     * Proves that the ticker-based timeout fires after exactly 10 real seconds, whatever the wall clock does.
     */
    @Test
    void fixMonotonicDeadlineIgnoresWallClockSteps() {
        Deadline deadline = new MonotonicDeadline(ticker, TIMEOUT);
        wall.advance(Duration.ofHours(-1));            // irrelevant: the deadline never reads the wall clock
        elapse(Duration.ofSeconds(9));
        assertFalse(deadline.hasExpired());
        assertEquals(Duration.ofSeconds(1), deadline.remaining());

        wall.advance(Duration.ofHours(2));
        elapse(Duration.ofSeconds(1));                 // exactly 10 s of real time
        assertTrue(deadline.hasExpired());
        assertEquals(Duration.ZERO, deadline.remaining());
    }

    /**
     * Proves that the test ticker rejects a negative advance, because a monotonic source never goes backwards.
     */
    @Test
    void manualTickerRefusesToGoBackwards() {
        assertThrows(IllegalArgumentException.class, () -> ticker.advance(Duration.ofNanos(-1)));
    }

    /**
     * Proves that the timeout still works when {@code nanoTime} wraps from a huge positive to a negative number, because it compares differences.
     */
    @Test
    void monotonicDeadlineIsCorrectAcrossNanoTimeOverflow() {
        // nanoTime may start anywhere, including near Long.MAX_VALUE. Differences still work.
        long[] now = {Long.MAX_VALUE - 5_000_000_000L};   // 5 s before overflow
        Ticker nearOverflow = () -> now[0];
        Deadline deadline = new MonotonicDeadline(nearOverflow, TIMEOUT);

        now[0] += 9_000_000_000L;                          // wraps to a large negative number
        assertTrue(now[0] < 0);
        assertFalse(deadline.hasExpired());
        now[0] += 1_000_000_000L;
        assertTrue(deadline.hasExpired());
    }
}
