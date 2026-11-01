package com.lld.splitwise;

import java.util.Map;
import java.util.TreeMap;

/**
 * Factory for the four {@link SplitStrategy} implementations.
 */
public final class Splits {

    /** Basis points in 100%: percents are whole basis points (33.33% is 3333), never a double. */
    public static final long FULL_BASIS_POINTS = 10_000;

    /** Not instantiable: only static factories. */
    private Splits() {
    }

    /** Everyone pays the same, with the spare cents going to the smallest user ids; the input values are ignored. */
    public static SplitStrategy equal() {
        return (total, input) -> proportional(total, input, false, false);
    }

    /** Each person pays exactly the amount given; the amounts must add up to the total or the split is rejected. */
    public static SplitStrategy exact() {
        return (total, input) -> {
            Map<String, Long> sorted = checked(total, input);
            long sum = 0;
            for (long v : sorted.values()) {
                sum = Math.addExact(sum, v);
            }
            if (sum != total) {
                throw new IllegalArgumentException("exact amounts add up to " + sum + " but the total is " + total);
            }
            return sorted;
        };
    }

    /** Each person pays a share given in basis points; they must add up to 10000, and rounding uses the largest remainder. */
    public static SplitStrategy percent() {
        return (total, input) -> proportional(total, input, true, true);
    }

    /** Each person pays in proportion to a weight (for example nights stayed); rounding uses the largest remainder. */
    public static SplitStrategy shares() {
        return (total, input) -> proportional(total, input, true, false);
    }

    /**
     * Validates the common rules and returns the input sorted by user id.
     *
     * @throws IllegalArgumentException if total is not positive, input is null or empty, or a value is null or negative
     */
    private static Map<String, Long> checked(long total, Map<String, Long> input) {
        if (total <= 0) {
            throw new IllegalArgumentException("total must be positive: " + total);
        }
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("at least one person must take part");
        }
        Map<String, Long> sorted = new TreeMap<>();
        for (Map.Entry<String, Long> e : input.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() < 0) {
                throw new IllegalArgumentException("user ids and values must be non-null and not negative");
            }
            sorted.put(e.getKey(), e.getValue());
        }
        return sorted;
    }

    /**
     * Shared body of equal, percent and shares: build the weights in user-id order and call the allocator.
     *
     * @param useValues true to use the input values as weights, false to give everybody weight 1
     * @param mustBeFull true to also require the weights to add up to 10000 basis points
     */
    private static Map<String, Long> proportional(long total, Map<String, Long> input, boolean useValues, boolean mustBeFull) {
        Map<String, Long> sorted = checked(total, input);
        long[] weights = new long[sorted.size()];
        int i = 0;
        long sum = 0;
        for (long v : sorted.values()) {
            weights[i] = useValues ? v : 1;
            sum = Math.addExact(sum, weights[i]);
            i++;
        }
        if (mustBeFull && sum != FULL_BASIS_POINTS) {
            throw new IllegalArgumentException("percents must add up to 10000 basis points but add up to " + sum);
        }
        long[] parts = Allocator.allocate(total, weights);
        Map<String, Long> result = new TreeMap<>();
        i = 0;
        for (String user : sorted.keySet()) {
            result.put(user, parts[i++]);
        }
        return result;
    }
}
