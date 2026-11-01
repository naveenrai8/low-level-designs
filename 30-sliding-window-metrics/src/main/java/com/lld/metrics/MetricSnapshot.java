package com.lld.metrics;

import java.util.Arrays;
import java.util.OptionalLong;

/**
 * An immutable picture of one metric: the windowed count, sum and histogram bin counts, plus the lifetime (cumulative) count and sum.
 *
 * <p>Because the histogram layout is fixed, two snapshots can be added bin by bin with {@link #merge}. The result is exactly what one metric fed both streams would hold. That is why a fleet of hosts can ship snapshots to one place and still get a correct p99.
 *
 * <p>The lifetime numbers only ever go up. Exporters use them for Prometheus {@code _sum} and {@code _count}, which must not go down when samples leave the window.
 */
public final class MetricSnapshot {

    private final long windowNanos;
    private final int slices;
    private final long count;
    private final long sum;
    private final long lifetimeCount;
    private final long lifetimeSum;
    private final long[] bins;

    /**
     * Creates a snapshot. The array is copied, so the caller cannot change it later.
     *
     * <p>Throws {@link IllegalArgumentException} if the array has the wrong length, a bin is negative, or {@code count} is not the total of the bins.
     */
    public MetricSnapshot(long windowNanos, int slices, long count, long sum, long lifetimeCount, long lifetimeSum, long[] bins) {
        if (bins.length != LogLinear.SIZE) {
            throw new IllegalArgumentException("bin array must have " + LogLinear.SIZE + " entries");
        }
        long total = 0;
        for (long b : bins) {
            if (b < 0) {
                throw new IllegalArgumentException("a bin count is negative");
            }
            total += b;
        }
        if (total != count) {
            throw new IllegalArgumentException("count " + count + " does not match the bin total " + total);
        }
        this.windowNanos = windowNanos;
        this.slices = slices;
        this.count = count;
        this.sum = sum;
        this.lifetimeCount = lifetimeCount;
        this.lifetimeSum = lifetimeSum;
        this.bins = bins.clone();
    }

    /** Returns a snapshot with no samples. */
    public static MetricSnapshot empty(long windowNanos, int slices) {
        return new MetricSnapshot(windowNanos, slices, 0, 0, 0, 0, new long[LogLinear.SIZE]);
    }

    /** Returns the window length in nanoseconds. */
    public long windowNanos() {
        return windowNanos;
    }

    /** Returns how many time slices the window was cut into. */
    public int slices() {
        return slices;
    }

    /** Returns how many samples are inside the window. */
    public long count() {
        return count;
    }

    /** Returns the sum of the samples inside the window (capped at Long.MAX_VALUE). */
    public long sum() {
        return sum;
    }

    /** Returns how many samples were ever recorded. It never goes down, so it is safe to export as a cumulative counter. */
    public long lifetimeCount() {
        return lifetimeCount;
    }

    /** Returns the sum of every sample ever recorded (capped at Long.MAX_VALUE). */
    public long lifetimeSum() {
        return lifetimeSum;
    }

    /** Returns a copy of the histogram bin counts. */
    public long[] binCounts() {
        return bins.clone();
    }

    /** Returns the value at quantile {@code q}, or empty when there are no samples. Throws {@link IllegalArgumentException} if q is NaN or outside 0..1. */
    public OptionalLong percentile(double q) {
        checkQuantile(q);
        if (count == 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(LogLinear.quantile(bins, count, q));
    }

    /** Returns the sum of this snapshot and {@code other}. Both must have the same window length and slice count, or an {@link IllegalArgumentException} is thrown. */
    public MetricSnapshot merge(MetricSnapshot other) {
        if (other.windowNanos != windowNanos || other.slices != slices) {
            throw new IllegalArgumentException("cannot merge snapshots with a different window length or slice count");
        }
        long[] added = new long[bins.length];
        for (int i = 0; i < added.length; i++) {
            added[i] = bins[i] + other.bins[i];
        }
        return new MetricSnapshot(windowNanos, slices, count + other.count, saturatingAdd(sum, other.sum), lifetimeCount + other.lifetimeCount, saturatingAdd(lifetimeSum, other.lifetimeSum), added);
    }

    /** Checks the quantile argument shared by every percentile method. */
    static void checkQuantile(double q) {
        if (Double.isNaN(q) || q < 0.0 || q > 1.0) {
            throw new IllegalArgumentException("q must be in [0, 1] but was " + q);
        }
    }

    /** Adds two non-negative longs and stops at Long.MAX_VALUE instead of wrapping to a negative number. */
    static long saturatingAdd(long a, long b) {
        long r = a + b;
        return r < 0 ? Long.MAX_VALUE : r;
    }

    /** Two snapshots are equal when every number and every bin count match. */
    @Override
    public boolean equals(Object o) {
        return o instanceof MetricSnapshot s && s.windowNanos == windowNanos && s.slices == slices && s.count == count && s.sum == sum
                && s.lifetimeCount == lifetimeCount && s.lifetimeSum == lifetimeSum && Arrays.equals(s.bins, bins);
    }

    /** Hash that agrees with {@link #equals}. */
    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(bins) + Long.hashCode(count);
    }

    /** Short text for debugging; it leaves out the bin array. */
    @Override
    public String toString() {
        return "MetricSnapshot[count=" + count + ", sum=" + sum + "]";
    }
}
