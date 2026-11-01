package com.lld.auction;

import java.time.Instant;

/**
 * The acknowledgement of one accepted bid.
 *
 * @param seq     1 for the first accepted bid, 2 for the next and so on, with no gaps
 * @param bidder  who bid
 * @param max     the proxy maximum they set
 * @param leading true when this bidder is the leader right after the bid
 * @param leader  the leader right after the bid
 * @param price   the visible price right after the bid
 * @param endsAt  the end time after the bid (soft close may have moved it)
 */
public record BidReceipt(long seq, String bidder, long max, boolean leading, String leader, long price, Instant endsAt) {
}
