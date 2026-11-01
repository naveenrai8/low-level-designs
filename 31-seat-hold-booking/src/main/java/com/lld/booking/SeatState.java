package com.lld.booking;

/** What a seat looks like to a caller: free, held by somebody for a short time, or sold. A HELD seat whose hold has run out is reported as AVAILABLE. */
public enum SeatState {
    /** Nobody owns the seat; anyone may hold it. */
    AVAILABLE,
    /** Someone holds the seat until the hold's expiry instant (exclusive). */
    HELD,
    /** The seat is sold for good. */
    BOOKED
}
