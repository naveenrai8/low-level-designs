package com.lld.mistakes;

import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.IntStream;

/**
 * <b>Fix (M6).</b> Money in {@code long} minor units, split by the <em>largest remainder</em>
 * method. Every share starts at its floor, and the leftover units go one each to the shares with
 * the largest fractional remainder (ties go to the lower index, so the result is deterministic).
 * The shares always sum to the total exactly, and no share is off from its exact proportion by
 * one unit or more.
 */
public final class MoneySplit {

    private MoneySplit() { }

    public static long[] equally(long totalMinor, int parts) {
        if (parts <= 0) {
            throw new IllegalArgumentException("parts must be positive: " + parts);
        }
        long[] weights = new long[parts];
        Arrays.fill(weights, 1);
        return byWeights(totalMinor, weights);
    }

    public static long[] byWeights(long totalMinor, long[] weights) {
        if (totalMinor < 0) {
            throw new IllegalArgumentException("total must not be negative: " + totalMinor);
        }
        long weightSum = 0;
        for (long w : weights) {
            if (w < 0) {
                throw new IllegalArgumentException("weights must not be negative");
            }
            weightSum = Math.addExact(weightSum, w);
        }
        if (weightSum == 0) {
            throw new IllegalArgumentException("weights must not all be zero");
        }
        long[] shares = new long[weights.length];
        long[] remainders = new long[weights.length];
        long allocated = 0;
        for (int i = 0; i < weights.length; i++) {
            long product = Math.multiplyExact(totalMinor, weights[i]);  // overflow fails loudly
            shares[i] = product / weightSum;
            remainders[i] = product % weightSum;
            allocated += shares[i];
        }
        long leftover = totalMinor - allocated;                         // always < weights.length
        int[] byRemainder = IntStream.range(0, weights.length).boxed()
                .sorted(Comparator.<Integer>comparingLong(i -> remainders[i]).reversed()
                        .thenComparingInt(i -> i))
                .mapToInt(Integer::intValue).toArray();
        for (int k = 0; k < leftover; k++) {
            shares[byRemainder[k]]++;
        }
        return shares;
    }
}
