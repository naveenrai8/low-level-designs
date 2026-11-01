package com.lld.booking;

import java.time.Instant;
import java.util.List;

/**
 * A short-lived claim on some seats of one show.
 *
 * @param holdId the id the caller uses to confirm or release
 * @param showId the show (movie screening, concert, flight, hotel date) the seats belong to
 * @param seatIds the held seats, sorted, never empty
 * @param expiresAt the first instant at which the hold is no longer valid (half-open: valid while now is before this)
 */
public record Hold(String holdId, String showId, List<String> seatIds, Instant expiresAt) {

    /** Copies the seat list so the record cannot be changed from outside. */
    public Hold {
        seatIds = List.copyOf(seatIds);
    }

    /** Says whether the hold is still valid at {@code now}: valid while now is before expiresAt, so it is expired AT expiresAt. */
    public boolean isLiveAt(Instant now) {
        return now.isBefore(expiresAt);
    }
}
