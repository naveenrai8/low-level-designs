package com.lld.auction;

/**
 * The final result of an auction. Once announced it never changes.
 *
 * @param sold   true when there is a winner
 * @param winner the winning bidder, or null when nothing sold
 * @param pays   what the winner pays in minor units, 0 when nothing sold
 */
public record Outcome(boolean sold, String winner, long pays) {

    /** The result when the reserve was not met or nobody bid. */
    public static final Outcome NO_SALE = new Outcome(false, null, 0);
}
