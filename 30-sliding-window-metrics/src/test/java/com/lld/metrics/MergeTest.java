package com.lld.metrics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** F1: histograms are mergeable, so a fleet can combine snapshots and still get the right percentile. */
class MergeTest {

    private final FakeTicker ticker = new FakeTicker();

    /** Builds a metric with the shared test window. */
    private RingWindowedMetric newMetric() {
        return new RingWindowedMetric(ticker, Duration.ofSeconds(10), 5);
    }

    /** Merging two hosts' snapshots equals one metric that saw both streams, bucket for bucket. */
    @Test
    void mergeEqualsOneInstanceFedBothStreams() {
        RingWindowedMetric hostA = newMetric();
        RingWindowedMetric hostB = newMetric();
        RingWindowedMetric both = newMetric();
        Random r = new Random(5);
        for (int i = 0; i < 50_000; i++) {
            long v = (long) Math.exp(8 + 1.5 * r.nextGaussian());
            (r.nextBoolean() ? hostA : hostB).record(v);
            both.record(v);
        }
        MetricSnapshot merged = hostA.snapshot().merge(hostB.snapshot());
        assertArrayEquals(both.snapshot().binCounts(), merged.binCounts());
        assertEquals(both.snapshot(), merged);
        assertEquals(both.percentile(0.99), merged.percentile(0.99));
    }

    /** Merge is commutative and has the empty snapshot as its identity. */
    @Test
    void mergeIsCommutativeWithIdentity() {
        RingWindowedMetric a = newMetric();
        RingWindowedMetric b = newMetric();
        a.record(10);
        b.record(2000);
        assertEquals(a.snapshot().merge(b.snapshot()), b.snapshot().merge(a.snapshot()));
        assertEquals(a.snapshot(), a.snapshot().merge(MetricSnapshot.empty(a.snapshot().windowNanos(), 5)));
    }

    /** The README example: 10,000 fast requests (10) on one host and 10 slow ones (1000) on another. Averaging the two p99s gives 505, but the true fleet p99 is 10. */
    @Test
    void averagingP99sIsWrong() {
        RingWindowedMetric busy = newMetric();
        RingWindowedMetric quiet = newMetric();
        for (int i = 0; i < 10_000; i++) {
            busy.record(10);
        }
        for (int i = 0; i < 10; i++) {
            quiet.record(1000);
        }
        long averaged = (busy.percentile(0.99).getAsLong() + quiet.percentile(0.99).getAsLong()) / 2;
        long truth = busy.snapshot().merge(quiet.snapshot()).percentile(0.99).getAsLong();
        assertEquals(505, averaged, 5);
        assertEquals(10, truth);
    }

    /** Snapshots with a different slice count cannot be merged either, even if the window length matches. */
    @Test
    void differentSliceCountRejected() {
        MetricSnapshot a = MetricSnapshot.empty(1_000_000_000L, 5);
        MetricSnapshot b = MetricSnapshot.empty(1_000_000_000L, 10);
        assertThrows(IllegalArgumentException.class, () -> a.merge(b));
    }

    /** A snapshot whose count does not match its bins is rejected at construction. */
    @Test
    void inconsistentSnapshotRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MetricSnapshot(1_000_000_000L, 5, 3, 0, 3, 0, new long[LogLinear.SIZE]));
    }

    /** Snapshots from metrics with different window lengths cannot be merged. */
    @Test
    void differentWindowsRejected() {
        MetricSnapshot a = MetricSnapshot.empty(1_000_000_000L, 5);
        MetricSnapshot b = MetricSnapshot.empty(2_000_000_000L, 5);
        assertThrows(IllegalArgumentException.class, () -> a.merge(b));
    }
}
