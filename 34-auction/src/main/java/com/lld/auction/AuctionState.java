package com.lld.auction;

import java.time.Instant;

/**
 * An immutable snapshot of everything that changes in an English auction. A bid builds a new snapshot and swaps it in, so a rejected bid cannot leave half a write behind.
 *
 * @param leader     the current leader, null before the first bid
 * @param leaderMax  the leader's proxy maximum, 0 before the first bid
 * @param price      the visible price, 0 before the first bid
 * @param accepted   how many bids were accepted so far
 * @param endsAt     the current end time (exclusive)
 * @param extensions how many times soft close has moved the end
 * @param outcome    null while unsettled, the final result after {@link Auction#settle()}
 */
public record AuctionState(String leader, long leaderMax, long price, long accepted, Instant endsAt, int extensions, Outcome outcome) {

    /** The state of a fresh auction that ends at the given instant. */
    public static AuctionState initial(Instant endsAt) {
        return new AuctionState(null, 0, 0, 0, endsAt, 0, null);
    }

    /** A copy of this snapshot with the final result attached. */
    public AuctionState settled(Outcome result) {
        return new AuctionState(leader, leaderMax, price, accepted, endsAt, extensions, result);
    }
}
