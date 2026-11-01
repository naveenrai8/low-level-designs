package com.lld.auction;

import java.time.Instant;

/**
 * The pure rules of proxy bidding: given a snapshot and a bid, return the next snapshot or throw. It touches no shared state, so every {@link Auction} implementation (safe or racy) shares exactly the same rules.
 */
final class Proxy {

    /** Not instantiable. */
    private Proxy() {
    }

    /**
     * Applies one bid to a snapshot. Every check and every addition happens before the new snapshot is built, so a throw means nothing was written.
     */
    static AuctionState apply(Item item, SoftClose soft, Instant now, AuctionState s, String bidder, long max) {
        if (bidder == null || bidder.isBlank() || max <= 0) {
            throw new IllegalArgumentException("bidder must not be blank and max must be > 0");
        }
        if (s.outcome() != null || !now.isBefore(s.endsAt())) {
            throw new BidRejectedException(BidRejectedException.Reason.CLOSED, "auction ended at " + s.endsAt());
        }
        if (bidder.equals(item.seller())) {
            throw new BidRejectedException(BidRejectedException.Reason.SELLER_BID, "the seller cannot bid");
        }
        String leader;
        long leaderMax;
        long price;
        if (s.leader() == null) {
            if (max < item.increment()) {
                throw new BidRejectedException(BidRejectedException.Reason.TOO_LOW, "first bid must be >= " + item.increment());
            }
            leader = bidder;
            leaderMax = max;
            price = item.increment();
        } else if (bidder.equals(s.leader())) {
            if (max <= s.leaderMax()) {
                throw new BidRejectedException(BidRejectedException.Reason.NOT_RAISING, "leader max is already " + s.leaderMax());
            }
            leader = bidder;
            leaderMax = max;
            price = s.price();
        } else {
            // If price + increment would not fit in a long, no bid can reach it.
            if (s.price() > Long.MAX_VALUE - item.increment() || max < Math.addExact(s.price(), item.increment())) {
                throw new BidRejectedException(BidRejectedException.Reason.TOO_LOW, "bid must be at least one increment above " + s.price());
            }
            if (max > s.leaderMax()) {
                leader = bidder;
                leaderMax = max;
                price = capPlus(s.leaderMax(), item.increment(), max);
            } else {
                // Lower or equal max: the earlier bid (the current leader) keeps the lead. On a tie the cap makes the price equal that max.
                leader = s.leader();
                leaderMax = s.leaderMax();
                price = capPlus(max, item.increment(), s.leaderMax());
            }
        }
        if (leaderMax >= item.reserve()) {
            price = Math.max(price, item.reserve());
        }
        Instant end = s.endsAt();
        int extensions = s.extensions();
        if (extensions < soft.maxExtensions() && !soft.window().isZero() && !now.isBefore(end.minus(soft.window()))) {
            end = end.plus(soft.window());
            extensions++;
        }
        return new AuctionState(leader, leaderMax, price, s.accepted() + 1, end, extensions, null);
    }

    /** Returns {@code min(cap, base + step)} without ever computing a sum that could overflow. Needs base, step and cap above 0. */
    private static long capPlus(long base, long step, long cap) {
        return base >= cap - step ? cap : Math.addExact(base, step);
    }

    /** Builds the acknowledgement for the bid that produced {@code next}. */
    static BidReceipt receipt(AuctionState next, String bidder, long max) {
        return new BidReceipt(next.accepted(), bidder, max, bidder.equals(next.leader()), next.leader(), next.price(), next.endsAt());
    }

    /** Decides the final result: no sale unless the leader's max reaches the reserve, else the leader pays the visible price (which was already lifted to the reserve and is never above their max). */
    static Outcome result(Item item, AuctionState s) {
        if (s.leader() == null || s.leaderMax() < item.reserve()) {
            return Outcome.NO_SALE;
        }
        return new Outcome(true, s.leader(), s.price());
    }
}
