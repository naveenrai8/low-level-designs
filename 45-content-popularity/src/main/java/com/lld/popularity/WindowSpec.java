package com.lld.popularity;

import java.time.Duration;

/**
 * The shape of a sliding window: it is cut into equal slices called buckets, and the window is exactly {@code buckets} of them.
 *
 * <p>Bucket number {@code n} covers the half-open span {@code [n * bucketNanos, (n + 1) * bucketNanos)}. At bucket number {@code cur} the live buckets are {@code cur - buckets + 1 .. cur}, so an event exactly one window old is already out.
 *
 * @param bucketNanos the length of one bucket in nanoseconds
 * @param buckets how many buckets make up the window
 */
public record WindowSpec(long bucketNanos, int buckets) {

    /** Checks both numbers are positive. */
    public WindowSpec {
        if (bucketNanos <= 0 || buckets <= 0) {
            throw new IllegalArgumentException("bucketNanos and buckets must be > 0");
        }
    }

    /** Builds a spec from a bucket length and a window length. The window must be a whole number of buckets. */
    public static WindowSpec of(Duration bucket, Duration window) {
        long b = bucket.toNanos();
        long w = window.toNanos();
        if (b <= 0 || w < b || w % b != 0) {
            throw new IllegalArgumentException("window must be a positive whole multiple of the bucket length");
        }
        return new WindowSpec(b, (int) (w / b));
    }

    /** Returns the bucket number a timestamp falls into. Uses floor division so negative readings still land in the right bucket. */
    public long indexOf(long nanos) {
        return Math.floorDiv(nanos, bucketNanos);
    }

    /** Returns true when bucket {@code idx} is still inside the window that ends at bucket {@code cur}. */
    public boolean isLive(long idx, long cur) {
        return idx > cur - buckets && idx <= cur;
    }

    /** Returns which ring slot bucket {@code idx} lives in. Many bucket numbers share a slot, one window apart. */
    public int slotOf(long idx) {
        return (int) Math.floorMod(idx, (long) buckets);
    }
}
