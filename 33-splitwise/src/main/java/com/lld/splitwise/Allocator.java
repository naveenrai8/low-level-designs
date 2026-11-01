package com.lld.splitwise;

import java.util.ArrayList;
import java.util.List;

/**
 * The largest-remainder method from Topic 11, in one place: shares proportional to weights that add up to the total exactly.
 */
final class Allocator {

    /** Not instantiable: only static helpers. */
    private Allocator() {
    }

    /**
     * Gives each weight its rounded-down share, then hands the left-over single units to the largest remainders.
     *
     * <p>Ties between equal remainders go to the lower index, so callers pass weights in user-id order to get the "smaller id first" rule. The leftover is always smaller than the number of weights, so each share is within 1 unit of the exact ratio.
     *
     * @param total the amount to split, at least 0
     * @param weights non-negative weights with a positive sum
     * @return one share per weight, summing exactly to {@code total}
     * @throws IllegalArgumentException if a weight is negative or all weights are 0
     * @throws ArithmeticException if {@code total * weight} does not fit in a long (we never wrap silently)
     */
    static long[] allocate(long total, long[] weights) {
        long weightSum = 0;
        for (long w : weights) {
            if (w < 0) {
                throw new IllegalArgumentException("weight must not be negative: " + w);
            }
            weightSum = Math.addExact(weightSum, w);
        }
        if (weightSum == 0) {
            throw new IllegalArgumentException("weights must not all be zero");
        }
        long[] parts = new long[weights.length];
        long[] remainders = new long[weights.length];
        long given = 0;
        for (int i = 0; i < weights.length; i++) {
            long product = Math.multiplyExact(total, weights[i]);
            parts[i] = product / weightSum;
            remainders[i] = product % weightSum;
            given += parts[i];
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < weights.length; i++) {
            order.add(i);
        }
        // Largest remainder first; List.sort is stable, so equal remainders keep index (user id) order.
        order.sort((a, b) -> Long.compare(remainders[b], remainders[a]));
        long leftover = total - given;
        for (int k = 0; k < leftover; k++) {
            parts[order.get(k)]++;
        }
        return parts;
    }
}
