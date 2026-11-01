package com.lld.auction;

import java.time.Clock;
import java.time.Instant;

/**
 * The BROKEN auction, kept to prove the bug. It has no lock: a bid reads the state, computes the next one, and writes it back. Anything that happens between the read and the write is lost.
 *
 * <p>Two defects show up. (1) Two bidders read the same old state, so both are told they lead and one bid vanishes. (2) A bid that passed the "still open" check can be written after {@link #settle()} announced a winner, so the auction ends up with two different outcomes. Never use this class outside the tests.
 */
public final class RacyAuction implements Auction {

    private final Item item;
    private final SoftClose soft;
    private final Clock clock;
    private final Gap gap;
    private volatile AuctionState state;

    /** Creates the broken auction with a {@link Gap} hook between the read and the write. */
    public RacyAuction(Item item, Instant endsAt, SoftClose soft, Clock clock, Gap gap) {
        this.item = item;
        this.soft = soft;
        this.clock = clock;
        this.gap = gap;
        this.state = AuctionState.initial(endsAt);
    }

    /** Read, compute, pause at the gap, write. The pause is where a second bid or the close can slip in. */
    @Override
    public BidReceipt bid(String bidder, long max) {
        gap.at(Gap.Phase.ENTERED);
        AuctionState next = Proxy.apply(item, soft, clock.instant(), state, bidder, max);
        gap.at(Gap.Phase.AFTER_READ);
        state = next;
        return Proxy.receipt(next, bidder, max);
    }

    /** Announces a result from an unlocked read, so a bid still in flight can overwrite it afterwards. */
    @Override
    public Outcome settle() {
        AuctionState cur = state;
        if (cur.outcome() == null) {
            if (clock.instant().isBefore(cur.endsAt())) {
                throw new IllegalStateException("auction still open until " + cur.endsAt());
            }
            AuctionState done = cur.settled(Proxy.result(item, cur));
            state = done;
            return done.outcome();
        }
        return cur.outcome();
    }

    /** Returns the current snapshot. */
    @Override
    public AuctionState state() {
        return state;
    }
}
