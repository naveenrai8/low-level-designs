package com.lld.booking;

import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/** One unit of inventory with its own lock. Every field except {@code id} and {@code lock} is guarded by {@code lock}. */
final class SeatCell {

    final String id;
    final ReentrantLock lock = new ReentrantLock();
    SeatState state = SeatState.AVAILABLE;
    String holdId;
    Instant expiresAt;

    /** Creates a free seat. */
    SeatCell(String id) {
        this.id = id;
    }

    /** Says whether the seat can be held right now: free, or held but past its expiry instant (the half-open rule, expired AT expiresAt). Caller holds the lock. */
    boolean isFree(Instant now) {
        return state == SeatState.AVAILABLE || (state == SeatState.HELD && !now.isBefore(expiresAt));
    }

    /** Returns the state a caller should see at {@code now}: an expired hold reads as AVAILABLE. Caller holds the lock. */
    SeatState visibleState(Instant now) {
        return isFree(now) ? SeatState.AVAILABLE : state;
    }

    /** Puts the seat back to free and forgets the hold. Caller holds the lock. */
    void reset() {
        state = SeatState.AVAILABLE;
        holdId = null;
        expiresAt = null;
    }
}
