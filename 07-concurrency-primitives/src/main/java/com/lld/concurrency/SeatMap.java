package com.lld.concurrency;

import java.util.Optional;

/**
 * Books seats for users. At most one user may ever hold a seat.
 */
public interface SeatMap {

    /**
     * Tries to book the seat for the user.
     *
     * @return true if {@code user} now holds {@code seat}; false if someone else already did
     */
    boolean book(int seat, String user);

    /**
     * Says who holds the seat, or empty if it is free.
     */
    Optional<String> holderOf(int seat);
}
