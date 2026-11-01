package com.lld.booking;

/** The hold exists but its expiry instant has been reached. On a confirm, the payment (if any) is recorded as needing a refund. */
public final class HoldExpiredException extends BookingException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception for the given id. */
    public HoldExpiredException(String id) {
        super("hold expired: " + id);
    }
}
