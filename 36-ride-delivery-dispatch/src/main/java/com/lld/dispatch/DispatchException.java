package com.lld.dispatch;

/** The one exception type of this module; the {@link Code} says what went wrong so callers and tests can match on it. */
public final class DispatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why a call was refused. */
    public enum Code {
        /** A coordinate is outside plus or minus one billion, or the cell size is not in 1..1,000,000. */
        OUT_OF_RANGE,
        /** The driver id was never registered. */
        UNKNOWN_DRIVER,
        /** The request id was never submitted. */
        UNKNOWN_REQUEST,
        /** A request with this id was already submitted. */
        DUPLICATE_REQUEST,
        /** The driver state table does not allow this move. */
        ILLEGAL_TRANSITION,
        /** The offer is not live: it timed out, was rejected, was accepted, or never existed. */
        STALE_OFFER,
        /** A scoring strategy returned a negative cost or one above the supported maximum. */
        BAD_COST
    }

    private final Code code;

    /**
     * Builds the exception.
     *
     * @param code the reason, never null
     * @param message a human readable detail
     */
    public DispatchException(Code code, String message) {
        super(code + ": " + message);
        this.code = code;
    }

    /** Returns the machine readable reason. */
    public Code code() {
        return code;
    }
}
