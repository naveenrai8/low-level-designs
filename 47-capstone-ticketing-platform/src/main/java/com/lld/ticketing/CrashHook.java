package com.lld.ticketing;

/**
 * A test seam: the checkout calls it at named step boundaries, and a test makes it throw to simulate the process dying right there. Production passes {@link #NONE}.
 *
 * <p>The checkout never catches what the hook throws, so the half-done record is exactly what a crashed process would have left in its database.
 */
@FunctionalInterface
public interface CrashHook {

    /** The named places a crash can happen. The names say what is already done. */
    enum Point {
        /** The record exists (state NEW) but no seat has been touched. */
        BEFORE_SEAT_HOLD,
        /** Seats are held and recorded. */
        AFTER_SEAT_HOLD,
        /** The ledger reserved the money, but the checkout has not written the hold id down yet (the known gap: a retry reserves again). */
        AFTER_AUTHORIZE_BEFORE_SAVE,
        /** The payment is reserved and recorded. */
        AFTER_PAYMENT_HOLD,
        /** A rollback voided the payment hold, but has not released the seats yet. */
        AFTER_VOID,
        /** A refund reversed the capture in the ledger, but the checkout has not recorded REFUNDED yet. */
        AFTER_REVERSE,
        /** The ledger moved the money, but the checkout has not written it down yet. */
        AFTER_CAPTURE_BEFORE_SAVE,
        /** The capture is recorded. */
        AFTER_CAPTURE,
        /** The booking is confirmed in Topic 31, but the checkout has not written it down yet. */
        AFTER_CONFIRM_BEFORE_SAVE,
        /** The booking is recorded together with the "notify later" note (the outbox). */
        AFTER_CONFIRM,
        /** About to hand the confirmation message to the notification service. */
        BEFORE_NOTIFY,
        /** The notification service accepted the message, but the outbox note is not cleared yet. */
        AFTER_NOTIFY_SUBMIT
    }

    /** Does nothing. */
    CrashHook NONE = (point, checkoutId) -> { };

    /** Called at each point; a test throws here to simulate a crash. */
    void at(Point point, String checkoutId);
}
