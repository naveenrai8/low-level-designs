package com.lld.auction;

/**
 * Thrown when a well-formed bid breaks an auction rule. A rejected bid changes nothing.
 */
public final class BidRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the bid was refused. */
    public enum Reason {
        /** The time is at or after the end, or the auction was already settled. */
        CLOSED,
        /** The bidder is the seller. */
        SELLER_BID,
        /** The bid is below the minimum (first bid: the increment; later bids: price + increment). */
        TOO_LOW,
        /** The current leader tried to set a maximum that is not higher than their own. */
        NOT_RAISING,
        /** Sealed-bid only: this bidder already has a bid in. */
        DUPLICATE
    }

    private final Reason reason;

    /** Creates the exception with its machine-readable reason and a human message. */
    public BidRejectedException(Reason reason, String message) {
        super(reason + ": " + message);
        this.reason = reason;
    }

    /** Returns why the bid was refused. */
    public Reason reason() {
        return reason;
    }
}
