package com.lld.concurrency;

import java.util.Optional;

/** Book a seat for a user; at most one user may ever hold a seat. */
public interface SeatMap {

    /** @return true if {@code user} now holds {@code seat}; false if someone else already did */
    boolean book(int seat, String user);

    Optional<String> holderOf(int seat);
}
