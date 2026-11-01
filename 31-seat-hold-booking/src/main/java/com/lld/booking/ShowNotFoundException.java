package com.lld.booking;

/** No show with this id is registered. */
public final class ShowNotFoundException extends BookingException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception for the given id. */
    public ShowNotFoundException(String id) {
        super("show not found: " + id);
    }
}
