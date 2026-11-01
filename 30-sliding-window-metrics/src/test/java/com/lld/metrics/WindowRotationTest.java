package com.lld.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Proves the ring forgets old samples at exactly the right tick, including long idle gaps. */
class WindowRotationTest {

    private final FakeTicker ticker = new FakeTicker();
    private final RingWindowedMetric metric = new RingWindowedMetric(ticker, Duration.ofSeconds(10), 5);

    /** The worked example from the README: window 10 s, 5 slices of 2 s, requests at t = 1, 3, 3, 9, 12. */
    @Test
    void readmeTimelineExample() {
        ticker.setSeconds(1);
        metric.record(10);
        ticker.setSeconds(3);
        metric.record(20);
        metric.record(30);
        ticker.setSeconds(9);
        metric.record(40);
        assertEquals(4, metric.count());
        ticker.setSeconds(12);
        metric.record(50);
        assertEquals(2, metric.count());
        assertEquals(90, metric.sum());
    }

    /** A sample in epoch 0 is still counted 1 ns before epoch 5 begins and is gone exactly at that tick (10 s), proving the live rule to the nanosecond. */
    @Test
    void exactBoundaryTick() {
        metric.record(7);
        ticker.setSeconds(10);
        ticker.advanceNanos(-1);
        assertEquals(1, metric.count(), "1 ns before the boundary it is still inside");
        ticker.advanceNanos(1);
        assertEquals(0, metric.count(), "at t = 10 s the slice's lap is over");
    }

    /** A sample recorded at t = 1 s is dropped when it is 9 s old, not 10 s: the window is (B-1) full slices plus the current partial one. */
    @Test
    void sampleIsDroppedAtAgeNineSecondsNotTen() {
        ticker.setSeconds(1);
        metric.record(7);
        ticker.setSeconds(9);
        ticker.advanceNanos(999_999_999L);
        assertEquals(1, metric.count(), "age just under 9 s");
        ticker.setSeconds(10);
        assertEquals(0, metric.count(), "age 9 s: its slice is the oldest and has aged out");
    }

    /** Only the first slice expires when time moves one slice at a time; the rest stay. */
    @Test
    void slicesExpireOneByOne() {
        for (int s = 0; s < 10; s += 2) {
            ticker.setSeconds(s);
            metric.record(1);
        }
        assertEquals(5, metric.count());
        ticker.setSeconds(10);
        assertEquals(4, metric.count());
        ticker.setSeconds(12);
        assertEquals(3, metric.count());
    }

    /** A gap of exactly B slices expires everything, and the write after it reuses the same slot cleanly. */
    @Test
    void gapOfExactlyBSlices() {
        metric.record(5);
        ticker.advanceSeconds(10);
        assertEquals(0, metric.count());
        assertEquals(0, metric.sum());
        metric.record(8);
        assertEquals(1, metric.count());
        assertEquals(8, metric.sum());
    }

    /** A gap of B-1 slices keeps the oldest sample alive: this is the off-by-one on the other side. */
    @Test
    void gapOfBMinusOneSlicesKeepsOldest() {
        metric.record(5);
        ticker.advanceSeconds(8);
        assertEquals(1, metric.count());
    }

    /** After a very long idle gap everything is gone, and the work done is one reset per slot written, not a loop over skipped slices. */
    @Test
    void longIdleGapExpiresEverythingWithConstantWork() {
        for (int s = 0; s < 10; s += 2) {
            ticker.setSeconds(s);
            metric.record(3);
        }
        long resetsBefore = metric.sliceResets();
        ticker.advanceSeconds(10L * 3600 * 24 * 365);
        assertEquals(0, metric.count());
        assertEquals(resetsBefore, metric.sliceResets(), "reading after the gap does no reset work at all");
        assertFalse(metric.percentile(0.5).isPresent());
        metric.record(9);
        assertEquals(resetsBefore + 1, metric.sliceResets(), "the write resets only its own slot");
        assertEquals(1, metric.count());
    }

    /** A slot from an older lap must not be counted even though it still holds data (the epoch number protects the reader). */
    @Test
    void staleSlotIsIgnoredByReaderWithoutAnyReset() {
        metric.record(4);
        ticker.advanceSeconds(6);
        metric.record(6);
        ticker.advanceSeconds(8);
        assertEquals(1, metric.count(), "the first sample (lap 0) is stale but its slot was never rewritten");
        assertEquals(6, metric.sum());
    }

    /** The ring works when the ticker starts negative, as System.nanoTime is allowed to. */
    @Test
    void negativeTickerReadingsWork() {
        FakeTicker neg = new FakeTicker(-7_300_000_000L);
        RingWindowedMetric m = new RingWindowedMetric(neg, Duration.ofSeconds(10), 5);
        m.record(1);
        neg.advanceSeconds(3);
        m.record(2);
        assertEquals(2, m.count());
        neg.advanceSeconds(8);
        assertEquals(1, m.count());
        neg.advanceSeconds(2);
        assertEquals(0, m.count());
    }

    /** Two slices is the smallest allowed ring. */
    @Test
    void twoSliceRing() {
        RingWindowedMetric m = new RingWindowedMetric(ticker, Duration.ofSeconds(4), 2);
        m.record(1);
        ticker.advanceSeconds(3);
        assertEquals(1, m.count());
        ticker.advanceSeconds(1);
        assertEquals(0, m.count(), "the window is 4 s: the slice is gone when the third slice starts");
    }
}
