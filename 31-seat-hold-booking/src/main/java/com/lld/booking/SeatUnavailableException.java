package com.lld.booking;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** At least one requested seat is held by somebody else or already sold. Nothing was held (all or nothing). */
public final class SeatUnavailableException extends BookingException {

    private static final long serialVersionUID = 1L;

    private final ArrayList<String> unavailable;

    /** Remembers every seat that blocked the request, sorted, so the caller can show them all at once. */
    public SeatUnavailableException(String showId, Set<String> unavailable) {
        super("seats unavailable in show " + showId + ": " + new TreeSet<>(unavailable));
        this.unavailable = new ArrayList<>(new TreeSet<>(unavailable));
    }

    /** Returns the blocking seats, sorted. */
    public List<String> unavailableSeats() {
        return List.copyOf(unavailable);
    }
}
