package com.lld.splitwise;

import java.util.Map;

/**
 * Turns one expense total into a share per person (the Strategy pattern: equal, exact, percent and shares are four implementations of this one interface).
 *
 * <p>Error contract: every implementation throws {@link IllegalArgumentException} (never returns a partial answer) when the total is not positive, the input is null or empty, an input value is negative, or the rule for that strategy is broken (exact amounts that do not add up to the total, percents that do not add up to 100.00%, weights that are all zero).
 *
 * <p>Success contract: the returned shares are never negative and add up to the total EXACTLY. The built-in strategies return a map sorted by user id (the group does not rely on that), and when a spare cent has to be handed out the tie is broken by user id (smaller id first).
 */
public interface SplitStrategy {

    /**
     * Splits {@code total} (minor units, for example cents) among the users in {@code input}.
     *
     * <p>What the values in {@code input} mean depends on the strategy: ignored for equal, amounts for exact, basis points (1/100 of a percent, 10000 = 100%) for percent, weights for shares.
     *
     * @param total the amount to split, in minor units, must be above 0
     * @param input user id to a strategy-specific number
     * @return user id to share, summing exactly to {@code total}
     */
    Map<String, Long> split(long total, Map<String, Long> input);
}
