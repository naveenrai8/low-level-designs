package com.lld.elevator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Small maths helpers for the simulation report. */
public final class Stats {

    /** Not instantiable: this class only holds static helpers. */
    private Stats() {
    }

    /** Returns the average, or throws {@link IllegalArgumentException} for an empty list. */
    public static double mean(List<Long> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("no values");
        }
        long sum = 0;
        for (long v : values) {
            sum += v;
        }
        return (double) sum / values.size();
    }

    /**
     * Returns the nearest-rank percentile: sort the values, take rank = ceil(p/100 * n), return the value at that rank (1-based).
     *
     * <p>The answer is always a value that really occurred (no blending between two values). {@code percent} is a whole number from 1 to 100; it is done in integers to avoid floating-point rounding at the edges.
     */
    public static long percentileNearestRank(List<Long> values, int percent) {
        if (values.isEmpty() || percent < 1 || percent > 100) {
            throw new IllegalArgumentException("need values and a percent from 1 to 100");
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (percent * sorted.size() + 99) / 100;
        return sorted.get(rank - 1);
    }
}
