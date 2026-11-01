package com.lld.scheduler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lld.scheduler.SchedulerContractTest.Kind;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Huge delays, periods and times must clamp to "very far away", never wrap around to the past and never throw. */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class OverflowTest {

    /** Proves a delay of Long.MAX_VALUE nanoseconds waits (it used to wrap to the past and fire at once). */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void maxNanosDelayDoesNotFireImmediately(Kind kind) {
        TimerScheduler s = kind.build(new FakeTicker());
        s.schedule(() -> { }, Duration.ofNanos(Long.MAX_VALUE));
        assertEquals(0, s.runDue());
        assertEquals(1, s.pending());
    }

    /** Proves a Duration too big for a long of nanoseconds is accepted and treated as "far away" instead of throwing ArithmeticException. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void hugeDurationsAreAcceptedAndClamped(Kind kind) {
        TimerScheduler s = kind.build(new FakeTicker());
        assertDoesNotThrow(() -> s.schedule(() -> { }, Duration.ofSeconds(Long.MAX_VALUE)));
        assertDoesNotThrow(() -> s.scheduleAtFixedRate(() -> { }, Duration.ofSeconds(Long.MAX_VALUE), Duration.ofSeconds(Long.MAX_VALUE)));
        assertDoesNotThrow(() -> s.scheduleWithFixedDelay(() -> { }, Duration.ZERO, Duration.ofSeconds(Long.MAX_VALUE)));
        assertEquals(1, s.runDue());
        assertEquals(3, s.pending());
    }

    /** Proves a fixed-rate period of Long.MAX_VALUE runs once and then waits; the next due time used to wrap to the past, so runDue never returned (the job cancels itself after 50 runs so a failure ends instead of hanging). */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void maxPeriodRunsOnceThenWaits(Kind kind) {
        TimerScheduler s = kind.build(new FakeTicker());
        AtomicInteger runs = new AtomicInteger();
        JobHandle[] h = new JobHandle[1];
        h[0] = s.scheduleAtFixedRate(() -> {
            if (runs.incrementAndGet() >= 50) {
                h[0].cancel();
            }
        }, Duration.ZERO, Duration.ofNanos(Long.MAX_VALUE));
        s.runDue();
        assertEquals(1, runs.get());
        assertEquals(1, s.pending());
    }

    /** Same as above for fixed-delay, whose next due time is "end of run + delay". */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void maxFixedDelayRunsOnceThenWaits(Kind kind) {
        TimerScheduler s = kind.build(new FakeTicker());
        AtomicInteger runs = new AtomicInteger();
        JobHandle[] h = new JobHandle[1];
        h[0] = s.scheduleWithFixedDelay(() -> {
            if (runs.incrementAndGet() >= 50) {
                h[0].cancel();
            }
        }, Duration.ZERO, Duration.ofNanos(Long.MAX_VALUE));
        s.runDue();
        assertEquals(1, runs.get());
    }

    /** Proves an absolute time of Long.MIN_VALUE means "long ago, run now" and not a wrapped far-future time. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void scheduleAtMinValueRunsNow(Kind kind) {
        TimerScheduler s = kind.build(new FakeTicker());
        AtomicInteger runs = new AtomicInteger();
        s.scheduleAt(runs::incrementAndGet, Long.MIN_VALUE);
        s.scheduleAt(runs::incrementAndGet, Long.MAX_VALUE);
        assertEquals(1, s.runDue());
        assertEquals(1, runs.get());
    }

    /** Proves a retry policy built from huge Durations does not throw at construction, and that a failed attempt with an enormous backoff leaves the job waiting (SCHEDULED), not stuck in RUNNING. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void hugeRetryBackoffLeavesTheJobWaiting(Kind kind) {
        assertDoesNotThrow(() -> new RetryPolicy(3, Duration.ofMillis(1), Duration.ofSeconds(Long.MAX_VALUE), new Random(1)));
        TimerScheduler s = kind.build(new FakeTicker());
        RetryPolicy huge = new RetryPolicy(3, Duration.ofMillis(Long.MAX_VALUE / 4), Duration.ofMillis(Long.MAX_VALUE / 4), new Random(1));
        JobHandle h = s.scheduleWithRetry(() -> {
            throw new IllegalStateException("x");
        }, Duration.ZERO, huge);

        s.runDue();

        assertEquals(JobStatus.SCHEDULED, h.status());
        assertEquals(1, s.pending());
        assertEquals(1, h.attempts());
    }
}
