package com.lld.auction;

/**
 * An English (ascending) auction with proxy bidding. A <i>proxy bid</i> is a secret maximum: the system raises the visible price for you, one <i>increment</i> at a time, only as far as needed to stay in front.
 *
 * <p>Money is {@code long} minor units. Time comes from an injected {@link java.time.Clock}. The auction is open for {@code now < endsAt}, so a bid at exactly {@code endsAt} is rejected.
 *
 * <p>Rules (these are assumed, modelled on eBay, see the README):
 * <ul>
 * <li>Price starts at 0. The first bid needs {@code max >= increment}, and then the visible price is one increment.
 * <li>A later bid from someone else needs {@code max >= price + increment}.
 * <li>A challenger with a higher max takes the lead and the price becomes {@code min(newMax, oldLeaderMax + increment)}.
 * <li>A challenger with a lower or equal max leaves the leader in place and the price becomes {@code min(leaderMax, challengerMax + increment)}.
 * <li>An equal max: the earlier bid keeps the lead and the price becomes that max.
 * <li>The leader may only raise their own max, and the visible price does not change.
 * <li>Once the leader's max is at least the reserve, the visible price is lifted to {@code max(price, reserve)}, so a bid must beat that. The winner pays the visible price. If the leader's max is below the reserve there is no sale.
 * <li>Prices are computed so they never overflow: near {@code Long.MAX_VALUE} the price is capped at the leader's max, and when {@code price + increment} no longer fits, every further bid is TOO_LOW.
 * </ul>
 */
public interface Auction {

    /**
     * Places a proxy bid.
     *
     * @return the receipt; its {@code seq} is the position in the accepted order
     * @throws IllegalArgumentException if the bidder is null or blank or max is 0 or less
     * @throws BidRejectedException     CLOSED at or after the end or after settle, SELLER_BID, TOO_LOW, NOT_RAISING; nothing is written
     */
    BidReceipt bid(String bidder, long max);

    /**
     * Closes the auction and returns the one final result. Calling it again returns the same result.
     *
     * @throws IllegalStateException if the clock is still before the end
     */
    Outcome settle();

    /** Returns a consistent snapshot of the auction. */
    AuctionState state();
}
