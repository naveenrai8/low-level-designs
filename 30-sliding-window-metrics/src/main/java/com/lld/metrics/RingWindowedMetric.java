package com.lld.metrics;

import java.time.Duration;
import java.util.Arrays;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * A ring of B time slices, each covering window/B of time, that implements {@link WindowedMetric}.
 *
 * <p>Time is cut into numbered slices ("epochs"): epoch = floor(now / sliceLength). Epoch e lives in slot {@code e mod B}. Each slot remembers which epoch it holds. A slot is live only when its epoch is in {@code (current - B, current]}.
 *
 * <p>Nothing ever loops to clear old slots. A reader just ignores a slot whose epoch is too old, and a writer resets only the one slot it needs when that slot still holds an older lap. So a ten-hour idle gap costs no reset work on a read, and one reset per slot actually written afterwards (at most B per lap).
 *
 * <p>Concurrency: one lock per slot guards its epoch, count, sum and histogram bins together, so the epoch check and the write are a single atomic step. Writers only contend with other writers in the same time slice. Every read takes B short locks, one at a time. A writer whose slot was already taken by a newer lap holds a sample older than the window, so it is dropped from the window (and counted in {@link #droppedLate()}) but still counts in the lifetime totals.
 */
public final class RingWindowedMetric implements WindowedMetric {

    private final Ticker ticker;
    private final long windowNanos;
    private final long sliceNanos;
    private final int sliceCount;
    private final Slice[] ring;
    private final Gap pickGap;
    private final Gap resetGap;
    private final AtomicLong resets = new AtomicLong();
    private final AtomicLong droppedLate = new AtomicLong();
    private final LongAdder lifetimeCount = new LongAdder();
    private final AtomicLong lifetimeSum = new AtomicLong();

    /** One time slice: its epoch number plus count, sum and histogram bins. Every field is guarded by the slice's own lock. */
    private static final class Slice {
        long epoch = Long.MIN_VALUE;
        long count;
        long sum;
        final long[] bins = new long[LogLinear.SIZE];
    }

    /** Creates a metric over {@code window}, cut into {@code slices} time slices (at least 2). The window in nanoseconds must divide evenly by the slice count. */
    public RingWindowedMetric(Ticker ticker, Duration window, int slices) {
        this(ticker, window, slices, Gap.NONE, Gap.NONE);
    }

    /**
     * Same as the public constructor, plus two test hooks: {@code pickGap} runs between picking a slice and locking it, and {@code resetGap} runs inside a reset between relabelling the slice and clearing it.
     *
     * <p>Fewer than 2 slices is rejected because the window would be only the current partial slice, and {@link #rate()} would have nothing to measure.
     */
    RingWindowedMetric(Ticker ticker, Duration window, int slices, Gap pickGap, Gap resetGap) {
        if (slices < 2) {
            throw new IllegalArgumentException("need at least two time slices");
        }
        long nanos = window.toNanos();
        if (nanos <= 0 || nanos % slices != 0) {
            throw new IllegalArgumentException("window must be positive and divide evenly into the slice count");
        }
        this.ticker = ticker;
        this.windowNanos = nanos;
        this.sliceCount = slices;
        this.sliceNanos = nanos / slices;
        this.pickGap = pickGap;
        this.resetGap = resetGap;
        this.ring = new Slice[slices];
        for (int i = 0; i < slices; i++) {
            ring[i] = new Slice();
        }
    }

    /** Returns the epoch (slice number) of the current time. Floor division keeps negative nanoTime readings correct. */
    private long currentEpoch() {
        return Math.floorDiv(ticker.read(), sliceNanos);
    }

    /** Records a sample into the slice that was current when the call started. Throws {@link IllegalArgumentException} for a negative value. */
    @Override
    public void record(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must not be negative: " + value);
        }
        long epoch = currentEpoch();
        Slice s = ring[(int) Math.floorMod(epoch, (long) sliceCount)];
        pickGap.pause();
        lifetimeCount.increment();
        lifetimeSum.accumulateAndGet(value, MetricSnapshot::saturatingAdd);
        synchronized (s) {
            if (s.epoch > epoch) {
                droppedLate.incrementAndGet();
                return;
            }
            if (s.epoch < epoch) {
                reset(s, epoch);
            }
            s.count++;
            s.sum = MetricSnapshot.saturatingAdd(s.sum, value);
            s.bins[LogLinear.indexOf(value)]++;
        }
    }

    /** Relabels a slice with the new epoch and clears it. The caller holds the slice's lock, so no reader can see the half-done state. */
    private void reset(Slice s, long newEpoch) {
        s.epoch = newEpoch;
        resetGap.pause();
        s.count = 0;
        s.sum = 0;
        Arrays.fill(s.bins, 0);
        resets.incrementAndGet();
    }

    /** Tells whether a slice's epoch is inside the live range for the reader's current epoch. */
    private boolean live(Slice s, long current) {
        return s.epoch > current - sliceCount && s.epoch <= current;
    }

    /** Returns the number of samples in the live slices. */
    @Override
    public long count() {
        long current = currentEpoch();
        long total = 0;
        for (Slice s : ring) {
            synchronized (s) {
                if (live(s, current)) {
                    total += s.count;
                }
            }
        }
        return total;
    }

    /** Returns the sum of the live slices (capped at Long.MAX_VALUE). */
    @Override
    public long sum() {
        long current = currentEpoch();
        long total = 0;
        for (Slice s : ring) {
            synchronized (s) {
                if (live(s, current)) {
                    total = MetricSnapshot.saturatingAdd(total, s.sum);
                }
            }
        }
        return total;
    }

    /** Returns samples per second over the (B-1) newest full slices; the current partial slice is left out so the number does not dip when a new slice starts. */
    @Override
    public double rate() {
        long current = currentEpoch();
        long total = 0;
        for (Slice s : ring) {
            synchronized (s) {
                if (live(s, current) && s.epoch < current) {
                    total += s.count;
                }
            }
        }
        return total / ((sliceNanos * (sliceCount - 1)) / 1_000_000_000.0);
    }

    /** Merges the live histograms and reads the quantile. Throws {@link IllegalArgumentException} for a bad q even when the window is empty. */
    @Override
    public OptionalLong percentile(double q) {
        MetricSnapshot.checkQuantile(q);
        return snapshot().percentile(q);
    }

    /** Copies every live slice into one immutable snapshot, and adds the lifetime totals. */
    @Override
    public MetricSnapshot snapshot() {
        long current = currentEpoch();
        long count = 0;
        long sum = 0;
        long[] merged = new long[LogLinear.SIZE];
        for (Slice s : ring) {
            synchronized (s) {
                if (live(s, current)) {
                    count += s.count;
                    sum = MetricSnapshot.saturatingAdd(sum, s.sum);
                    for (int i = 0; i < merged.length; i++) {
                        merged[i] += s.bins[i];
                    }
                }
            }
        }
        return new MetricSnapshot(windowNanos, sliceCount, count, sum, lifetimeCount.sum(), lifetimeSum.get(), merged);
    }

    /** Returns how many times a slot was reset for a new lap. A test uses it to prove a long idle gap does constant work. */
    public long sliceResets() {
        return resets.get();
    }

    /** Returns how many samples were dropped from the window because their slot already belonged to a newer lap (they were older than the window anyway). */
    public long droppedLate() {
        return droppedLate.get();
    }
}
