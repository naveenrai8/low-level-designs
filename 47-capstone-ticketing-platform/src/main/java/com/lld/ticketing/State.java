package com.lld.ticketing;

/**
 * Where one checkout is in its life. It moves forward only: NEW, SEATS_HELD, PAYMENT_HELD, CAPTURED, then one of the end states.
 *
 * <p>CONFIRMED means the buyer owns the seats and has been charged. REJECTED means the seats were not available. DECLINED means the buyer could not pay. CANCELLED means the seat hold ran out before the money moved, so seats and payment hold were given back. REFUNDED means the money was taken but the hold had expired, so the capture was reversed. NEEDS_REVIEW means the money was taken and the booking state is unknown, so the money is left alone and a human is told.
 */
public enum State {
    /** The checkout was accepted and nothing has been done yet. */
    NEW,
    /** The seats are held. */
    SEATS_HELD,
    /** The seats are held and the money is reserved (authorised) but not moved. */
    PAYMENT_HELD,
    /** The money has moved to the venue but the booking is not confirmed yet. */
    CAPTURED,
    /** End state: booked and paid. */
    CONFIRMED,
    /** End state: some seats were not available, nothing was held or charged. */
    REJECTED,
    /** End state: the buyer's wallet could not cover the price, the seats were released. */
    DECLINED,
    /** End state: the seat hold expired before the capture, seats and payment hold were released. */
    CANCELLED,
    /** End state: the capture happened but the confirm failed because the hold expired, so the capture was reversed. */
    REFUNDED,
    /** End state: the money was captured and we cannot prove whether the booking was made (hold forgotten, or booked under another key). Nothing is changed; a person or a reconciliation job decides. */
    NEEDS_REVIEW;

    /** Says whether the saga is finished: a retry of a finished checkout only replays the answer. */
    public boolean isTerminal() {
        return this != NEW && this != SEATS_HELD && this != PAYMENT_HELD && this != CAPTURED;
    }
}
