package com.lld.time;

import java.util.Optional;

/** Seat holds with a TTL, the core of every booking system (Topic 31). */
public interface HoldRegistry {

    /** @return the new hold, or empty if someone else holds the seat right now. */
    Optional<Hold> tryHold(String seat, String user);

    /** @return the hold that is active right now, if any. */
    Optional<Hold> activeHold(String seat);

    /** Gives a hold back early (user cancelled, or it was confirmed into a booking). */
    boolean release(Hold hold);

    /** Entries still in memory, active or not yet swept. */
    int size();
}
