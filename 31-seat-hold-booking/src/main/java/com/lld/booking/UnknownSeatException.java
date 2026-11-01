package com.lld.booking;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** The request names a seat that does not exist in the show. This is a client mistake, unlike SeatUnavailableException, which is a race. */
public final class UnknownSeatException extends BookingException {

    private static final long serialVersionUID = 1L;

    private final ArrayList<String> unknown;

    /** Remembers the unknown seat ids, sorted. */
    public UnknownSeatException(String showId, Set<String> unknown) {
        super("unknown seats in show " + showId + ": " + new TreeSet<>(unknown));
        this.unknown = new ArrayList<>(new TreeSet<>(unknown));
    }

    /** Returns the unknown seat ids, sorted. */
    public List<String> unknownSeats() {
        return List.copyOf(unknown);
    }
}
