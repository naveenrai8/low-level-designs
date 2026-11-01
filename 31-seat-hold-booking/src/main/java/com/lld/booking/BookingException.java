package com.lld.booking;

/** Base of every business error of the booking service. All are unchecked: the caller decides which ones it wants to translate. */
public abstract class BookingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with a message for humans. */
    protected BookingException(String message) {
        super(message);
    }
}
