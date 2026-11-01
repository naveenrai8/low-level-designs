package com.lld.booking;

/** No hold with this id: never issued, released, or forgotten after the retention period. */
public final class HoldNotFoundException extends BookingException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception for the given id. */
    public HoldNotFoundException(String id) {
        super("hold not found: " + id);
    }
}
