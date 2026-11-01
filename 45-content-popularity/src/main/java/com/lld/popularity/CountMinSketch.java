package com.lld.popularity;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * A Count-Min sketch: a fixed-size table of counters that estimates how often each item was seen, using memory that does not grow with the number of distinct items.
 *
 * <p>Shape: {@code depth} rows of {@code width} counters. Adding an item bumps one counter in every row (each row picks the column with its own hash). Asking for an item returns the smallest of its counters. Collisions can only add to a counter, so the answer is never below the truth.
 *
 * <p>Sizing from the error budget: {@code width = ceil(e / eps)} and {@code depth = ceil(ln(1 / delta))}. Then, with probability at least {@code 1 - delta}, the estimate is at most {@code eps * N} too high, where N is the total of all counts. Source: Cormode and Muthukrishnan, "An Improved Data Stream Summary: The Count-Min Sketch and its Applications", J. Algorithms 55(1), 2005.
 *
 * <p>Hash: the item is hashed once to 64 bits with FNV-1a over its chars. Row r then uses {@code splitmix64(hash XOR seed_r) mod width}, where the row seeds are drawn from a {@link SplittableRandom} started from the caller's seed. Different seeds per row make the rows behave as independent hashes, and the caller's seed makes the whole thing repeatable.
 */
public final class CountMinSketch {

    private final int width;
    private final int depth;
    private final long[] table;
    private final long[] rowSeeds;
    private long total;

    /** Creates a sketch sized for error {@code eps} (a fraction of N) that holds with probability {@code 1 - delta}. */
    public CountMinSketch(double eps, double delta, long seed) {
        this.width = widthFor(eps);
        this.depth = depthFor(delta);
        this.table = new long[width * depth];
        this.rowSeeds = new long[depth];
        SplittableRandom rnd = new SplittableRandom(seed);
        for (int r = 0; r < depth; r++) {
            rowSeeds[r] = rnd.nextLong();
        }
    }

    /** Returns {@code ceil(e / eps)}, the number of counters per row. */
    public static int widthFor(double eps) {
        if (!(eps > 0 && eps < 1)) {
            throw new IllegalArgumentException("eps must be in (0, 1)");
        }
        return (int) Math.ceil(Math.E / eps);
    }

    /** Returns {@code ceil(ln(1 / delta))}, the number of rows. */
    public static int depthFor(double delta) {
        if (!(delta > 0 && delta < 1)) {
            throw new IllegalArgumentException("delta must be in (0, 1)");
        }
        return (int) Math.ceil(Math.log(1.0 / delta));
    }

    /** Adds {@code count} (not negative) occurrences of {@code item}. */
    public void add(String item, long count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0");
        }
        long h = hash64(item);
        for (int r = 0; r < depth; r++) {
            table[r * width + column(h, r)] += count;
        }
        total += count;
    }

    /** Returns the estimated count of {@code item}: never lower than the true count, and usually equal to it. */
    public long estimate(String item) {
        long h = hash64(item);
        long best = Long.MAX_VALUE;
        for (int r = 0; r < depth; r++) {
            best = Math.min(best, table[r * width + column(h, r)]);
        }
        return best;
    }

    /** Returns N, the sum of every count added so far. */
    public long total() {
        return total;
    }

    /** Returns the number of counters per row. */
    public int width() {
        return width;
    }

    /** Returns the number of rows. */
    public int depth() {
        return depth;
    }

    /** Returns the memory used by the counter table in bytes: {@code width * depth * 8}. The row seeds add {@code depth * 8} more. */
    public long counterBytes() {
        return 8L * width * depth;
    }

    /** Sets every counter back to zero so the object can be reused when a bucket expires. */
    public void clear() {
        Arrays.fill(table, 0L);
        total = 0;
    }

    /** Picks the column for hash {@code h} in row {@code r}. */
    private int column(long h, int r) {
        return (int) Long.remainderUnsigned(mix(h ^ rowSeeds[r]), width);
    }

    /** Hashes a string to 64 bits with FNV-1a. It is stable across runs, unlike an identity-based hash. */
    private static long hash64(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h = (h ^ s.charAt(i)) * 0x100000001b3L;
        }
        return h;
    }

    /** The splitmix64 finaliser: spreads the bits so that nearby inputs give unrelated outputs. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
