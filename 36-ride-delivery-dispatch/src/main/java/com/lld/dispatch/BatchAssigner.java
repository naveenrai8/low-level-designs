package com.lld.dispatch;

/**
 * Picks which request gets which driver from a table of costs.
 * <p>Error contract: every row must have the same length; a cost at or above {@link #FORBIDDEN} means "this pair is not allowed" and is never chosen.
 */
public interface BatchAssigner {

    /** Marks a pair that must not be chosen (for example a driver who already declined this request). */
    long FORBIDDEN = 1L << 50;

    /**
     * Chooses a pairing. Each row (request) gets at most one column (driver) and each column at most one row.
     *
     * @param cost cost[request][driver]
     * @return for each request the chosen driver column, or -1 if it got none
     */
    int[] assign(long[][] cost);
}
