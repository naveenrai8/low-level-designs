package com.lld.booking;

/**
 * F3: how many units a show may sell compared with its physical capacity. Airlines sell more tickets than seats because some passengers never show up.
 */
@FunctionalInterface
public interface OverbookPolicy {

    /** Sell exactly the capacity. */
    OverbookPolicy NONE = capacity -> capacity;

    /**
     * Returns how many units may be sold for a show with the given capacity. It must be at least the capacity.
     */
    int sellable(int capacity);

    /**
     * Returns a policy that sells {@code extra} units on top of the capacity.
     */
    static OverbookPolicy fixedAllowance(int extra) {
        if (extra < 0) {
            throw new IllegalArgumentException("allowance must not be negative: " + extra);
        }
        return capacity -> capacity + extra;
    }

    /**
     * Returns a policy that sells a percentage on top of the capacity, rounded down (5 percent of 150 seats is 7 extra).
     */
    static OverbookPolicy percent(int percent) {
        if (percent < 0) {
            throw new IllegalArgumentException("percent must not be negative: " + percent);
        }
        return capacity -> capacity + capacity * percent / 100;
    }
}
