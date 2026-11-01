package com.lld.auction;

import java.time.Duration;

/**
 * Anti-sniping setting. A bid accepted in the last {@code window} before the end pushes the end back by {@code window}. "Last window" includes the instant {@code endsAt - window} itself.
 *
 * @param window        how long the quiet period must be; zero means the end never moves
 * @param maxExtensions how many times the end may be pushed back; use {@link Integer#MAX_VALUE} for no cap
 */
public record SoftClose(Duration window, int maxExtensions) {

    /** A plain hard close: the end never moves. */
    public static final SoftClose NONE = new SoftClose(Duration.ZERO, 0);

    /** Checks the fields. Throws {@link IllegalArgumentException} for a null or negative window or a negative cap. */
    public SoftClose {
        if (window == null || window.isNegative() || maxExtensions < 0) {
            throw new IllegalArgumentException("window must be >= 0 and maxExtensions >= 0");
        }
    }

    /** Soft close with no cap on the number of extensions. */
    public static SoftClose unlimited(Duration window) {
        return new SoftClose(window, Integer.MAX_VALUE);
    }
}
