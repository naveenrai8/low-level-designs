package com.lld.booking;

/** The hold was already turned into a booking (by a different idempotency key), so it cannot be confirmed again or released. */
public final class AlreadyConfirmedException extends BookingException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception for the given id. */
    public AlreadyConfirmedException(String id) {
        super("hold already confirmed: " + id);
    }
}
