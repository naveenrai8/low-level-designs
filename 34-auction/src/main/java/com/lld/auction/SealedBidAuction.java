package com.lld.auction;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A sealed-bid second-price auction. Bidders send one hidden amount each before {@code endsAt} (a bid at exactly {@code endsAt} is rejected). After the end {@link #settle()} applies {@link Vickrey}. One lock guards the bid list so "submit or close first" has one answer.
 */
public final class SealedBidAuction {

    private final Item item;
    private final Instant endsAt;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<SealedBid> bids = new ArrayList<>();
    private Outcome outcome;

    /** Creates the auction. The item's increment is not used by sealed bids. */
    public SealedBidAuction(Item item, Instant endsAt, Clock clock) {
        if (item == null || endsAt == null || clock == null) {
            throw new IllegalArgumentException("no argument may be null");
        }
        this.item = item;
        this.endsAt = endsAt;
        this.clock = clock;
    }

    /**
     * Submits the bidder's only bid and returns its arrival number.
     *
     * @throws IllegalArgumentException if the bidder is blank or the amount is 0 or less
     * @throws BidRejectedException     CLOSED at or after the end, SELLER_BID, DUPLICATE for a second bid from the same bidder (a sealed bid is final)
     */
    public long submit(String bidder, long amount) {
        if (bidder == null || bidder.isBlank() || amount <= 0) {
            throw new IllegalArgumentException("bidder must not be blank and amount must be > 0");
        }
        lock.lock();
        try {
            if (outcome != null || !clock.instant().isBefore(endsAt)) {
                throw new BidRejectedException(BidRejectedException.Reason.CLOSED, "sealed auction ended at " + endsAt);
            }
            if (bidder.equals(item.seller())) {
                throw new BidRejectedException(BidRejectedException.Reason.SELLER_BID, "the seller cannot bid");
            }
            if (bids.stream().anyMatch(b -> b.bidder().equals(bidder))) {
                throw new BidRejectedException(BidRejectedException.Reason.DUPLICATE, bidder + " already bid");
            }
            long seq = bids.size() + 1L;
            bids.add(new SealedBid(seq, bidder, amount));
            return seq;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Opens the envelopes after the end and returns the one result; repeat calls return the same object.
     *
     * @throws IllegalStateException if the clock is still before the end
     */
    public Outcome settle() {
        lock.lock();
        try {
            if (outcome == null) {
                if (clock.instant().isBefore(endsAt)) {
                    throw new IllegalStateException("sealed auction still open until " + endsAt);
                }
                outcome = Vickrey.settle(bids, item.reserve());
            }
            return outcome;
        } finally {
            lock.unlock();
        }
    }
}
