package com.lld.auction;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The safe English auction: one lock per auction. Reading the clock, reading the leader, computing and writing the new state all happen inside that lock, so bids and the close are totally ordered.
 *
 * <p>Why a lock and not a single-writer queue: one auction has one tiny critical section (a few comparisons), the caller wants the receipt back right away, and a lock gives that without a thread hand-off. A queue wins when you have millions of auctions or need replay, see the README.
 */
public final class LockedAuction implements Auction {

    private final Item item;
    private final SoftClose soft;
    private final Clock clock;
    private final Gap gap;
    private final ReentrantLock lock = new ReentrantLock();
    private AuctionState state;

    /** Creates an auction that ends at {@code endsAt} with no test hook. */
    public LockedAuction(Item item, Instant endsAt, SoftClose soft, Clock clock) {
        this(item, endsAt, soft, clock, Gap.NONE);
    }

    /** Creates an auction with a {@link Gap} hook so tests can force an interleaving. */
    public LockedAuction(Item item, Instant endsAt, SoftClose soft, Clock clock, Gap gap) {
        if (item == null || endsAt == null || soft == null || clock == null || gap == null) {
            throw new IllegalArgumentException("no argument may be null");
        }
        this.item = item;
        this.soft = soft;
        this.clock = clock;
        this.gap = gap;
        this.state = AuctionState.initial(endsAt);
    }

    /** Takes the lock, then reads the clock and the state, computes the next state and swaps it in. The clock is read inside the lock on purpose: that is what makes "bid or close first" a single answer. */
    @Override
    public BidReceipt bid(String bidder, long max) {
        gap.at(Gap.Phase.ENTERED);
        lock.lock();
        try {
            AuctionState next = Proxy.apply(item, soft, clock.instant(), state, bidder, max);
            gap.at(Gap.Phase.AFTER_READ);
            state = next;
            return Proxy.receipt(next, bidder, max);
        } finally {
            lock.unlock();
        }
    }

    /** Under the lock, announces the result once; later calls return the same object. */
    @Override
    public Outcome settle() {
        lock.lock();
        try {
            if (state.outcome() == null) {
                if (clock.instant().isBefore(state.endsAt())) {
                    throw new IllegalStateException("auction still open until " + state.endsAt());
                }
                state = state.settled(Proxy.result(item, state));
            }
            return state.outcome();
        } finally {
            lock.unlock();
        }
    }

    /** Returns the current snapshot, read under the lock. */
    @Override
    public AuctionState state() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    /** How many threads are queued for the lock right now; tests use it to know a second bidder is really blocked. */
    int waiting() {
        return lock.getQueueLength();
    }
}
