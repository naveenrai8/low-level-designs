package com.lld.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Proves the error contract written on {@link WindowedMetric}. */
class MetricContractTest {

    private final FakeTicker ticker = new FakeTicker();
    private final WindowedMetric metric = new RingWindowedMetric(ticker, Duration.ofSeconds(10), 5);

    /** A negative value is rejected and changes nothing. */
    @Test
    void negativeValueRejected() {
        assertThrows(IllegalArgumentException.class, () -> metric.record(-1));
        assertEquals(0, metric.count());
    }

    /** Quantiles outside 0..1 and NaN are rejected, even on an empty window. */
    @Test
    void badQuantileRejected() {
        assertThrows(IllegalArgumentException.class, () -> metric.percentile(-0.01));
        assertThrows(IllegalArgumentException.class, () -> metric.percentile(1.01));
        assertThrows(IllegalArgumentException.class, () -> metric.percentile(Double.NaN));
    }

    /** An empty window gives empty percentiles and zero counters, never a fake 0 latency. */
    @Test
    void emptyWindow() {
        assertFalse(metric.percentile(0.99).isPresent());
        assertEquals(0, metric.count());
        assertEquals(0, metric.sum());
        assertEquals(0.0, metric.rate());
        assertEquals(0, metric.snapshot().count());
    }

    /** q = 0 gives the smallest sample and q = 1 the largest. */
    @Test
    void extremeQuantiles() {
        for (long v : new long[] {5, 50, 100}) {
            metric.record(v);
        }
        assertEquals(5, metric.percentile(0.0).getAsLong());
        assertEquals(100, metric.percentile(1.0).getAsLong());
        assertEquals(50, metric.percentile(0.5).getAsLong());
    }

    /** Rate counts only the 4 full 2 s slices before the current one: 8 samples in them is 8 / 8 s = 1 per second, and the current slice is left out. */
    @Test
    void rateUsesFullSlicesOnly() {
        ticker.setSeconds(2);
        for (int i = 0; i < 8; i++) {
            metric.record(1);
        }
        ticker.setSeconds(4);
        metric.record(1);
        assertEquals(1.0, metric.rate(), 1e-9);
        assertEquals(9, metric.count(), "count still includes the partial slice");
    }

    /** A steady 5 per second measured just after and just before a slice boundary gives the same rate (the old full-window divisor dipped by 1/B right after each boundary). */
    @Test
    void steadyRateDoesNotSawTooth() {
        recordEvery200ms(0, 26_000);
        ticker.setSeconds(26);
        ticker.advanceNanos(1);
        double justAfter = metric.rate();
        recordEvery200ms(26_000, 28_000);
        ticker.setSeconds(28);
        ticker.advanceNanos(-1);
        double justBefore = metric.rate();
        assertEquals(5.0, justAfter, 1e-9);
        assertEquals(5.0, justBefore, 1e-9);
    }

    /** The sum stops at Long.MAX_VALUE instead of wrapping to a negative number. */
    @Test
    void sumSaturates() {
        metric.record(Long.MAX_VALUE);
        metric.record(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, metric.sum());
        assertEquals(2, metric.count());
    }

    /** A huge value is accepted and lands in the top bucket. */
    @Test
    void valueAboveTrackableRangeIsClamped() {
        metric.record(Long.MAX_VALUE);
        assertTrue(metric.percentile(0.5).getAsLong() >= LogLinear.MAX_TRACKABLE);
    }

    /** The constructor rejects zero buckets, fewer than two slices, a zero window and a window that does not divide evenly. */
    @Test
    void constructorValidation() {
        assertThrows(IllegalArgumentException.class, () -> new RingWindowedMetric(ticker, Duration.ofSeconds(10), 1));
        assertThrows(IllegalArgumentException.class, () -> new RingWindowedMetric(ticker, Duration.ZERO, 5));
        assertThrows(IllegalArgumentException.class, () -> new RingWindowedMetric(ticker, Duration.ofNanos(10), 3));
    }

    /** A snapshot is a copy: samples recorded afterwards do not change it. */
    @Test
    void snapshotIsImmutableCopy() {
        metric.record(10);
        MetricSnapshot s = metric.snapshot();
        metric.record(20);
        assertEquals(1, s.count());
        long[] copy = s.binCounts();
        copy[10] = 99;
        assertEquals(1, s.binCounts()[10]);
    }

    /** Records one sample every 200 ms (a steady 5 per second) for the half-open time range, moving the clock as it goes. */
    private void recordEvery200ms(int fromMs, int toMs) {
        for (int ms = fromMs; ms < toMs; ms += 200) {
            ticker.setSeconds(0);
            ticker.advanceMillis(ms);
            metric.record(1);
        }
    }
}
