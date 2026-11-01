package com.lld.auction;

/**
 * One sealed bid: a single amount, hidden until the close.
 *
 * @param seq    the order it arrived in, 1 first; used to break ties
 * @param bidder who bid
 * @param amount the amount in minor units
 */
public record SealedBid(long seq, String bidder, long amount) {
}
