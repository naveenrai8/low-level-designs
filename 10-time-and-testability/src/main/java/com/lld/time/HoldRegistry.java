package com.lld.time;

import java.util.Optional;

/**
 * Seat holds with a TTL, the core of every booking system (Topic 31).
 */
public interface HoldRegistry {

    /**
     * Tries to hold the seat for the user.
     * 
     * @return the new hold, or empty if someone else holds the seat right now.
     */
    Optional<Hold> tryHold(String seat, String user);

    /**
     * Looks up the seat's current hold.
     * 
     * @return the hold that is active right now, if any.
     */
    Optional<Hold> activeHold(String seat);

    /**
     * Gives a hold back early (the user cancelled, or it was confirmed into a booking). Returns true if this hold was removed.
     */
    boolean release(Hold hold);

    /**
     * Counts entries still in memory, active or not yet swept.
     */
    int size();
}
