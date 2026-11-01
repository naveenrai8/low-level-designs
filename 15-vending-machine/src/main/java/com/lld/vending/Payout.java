package com.lld.vending;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * A pile of coins handed back to the customer: coin value (minor units) to how many of that coin.
 *
 * @param coins coin value to count, never changed after creation, zero counts removed
 */
public record Payout(Map<Long, Integer> coins) {

    /** Makes a private sorted copy and drops zero counts, so two payouts of the same coins are equal. */
    public Payout {
        TreeMap<Long, Integer> copy = new TreeMap<>();
        coins.forEach((value, count) -> {
            if (count < 0) {
                throw new IllegalArgumentException("negative count for coin " + value);
            }
            if (count > 0) {
                copy.put(value, count);
            }
        });
        coins = Collections.unmodifiableMap(copy);
    }

    /** Returns the empty payout. */
    public static Payout none() {
        return new Payout(Map.of());
    }

    /** Returns the total value of all coins in minor units. */
    public long total() {
        long sum = 0;
        for (Map.Entry<Long, Integer> e : coins.entrySet()) {
            sum += e.getKey() * e.getValue();
        }
        return sum;
    }
}
