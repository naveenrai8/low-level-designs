package com.lld.ticketing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lld.booking.SeatState;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Round 2 (15 to 30 min): one key across the whole flow, and a crash at every step boundary. */
class RoundTwoTest {

    /** Proves that a crash at any point on the happy path, followed by a retry with the same key, ends with one booking, one capture, one delivered message and a balanced ledger (the three rollback points and the known authorize gap have their own tests). */
    @ParameterizedTest
    @EnumSource(value = CrashHook.Point.class, mode = EnumSource.Mode.EXCLUDE, names = {"AFTER_AUTHORIZE_BEFORE_SAVE", "AFTER_VOID", "AFTER_REVERSE"})
    void crashAtAnyPointThenRetryConverges(CrashHook.Point point) {
        Rig r = new Rig();
        r.crashOnce(point);
        assertThrows(Rig.SimulatedCrash.class, () -> r.checkout.checkout("c1", Rig.request("ann", "A1")));
        CheckoutResult res = r.checkout.checkout("c1", Rig.request("ann", "A1"));
        assertEquals(State.CONFIRMED, res.state());
        r.notifications.runDue();
        assertEquals(1, r.captures());
        assertEquals(Rig.PRICE, r.ledger.naturalBalance("venue"));
        assertEquals(Rig.WALLET - Rig.PRICE, r.available("ann"));
        assertEquals(SeatState.BOOKED, r.bookings.seatState("s1", "A1"));
        assertEquals(1, r.delivered.size());
        assertEquals(0, r.ledger.trialBalance("USD"));
        assertTrue(r.bookings.refundsNeeded().isEmpty());
    }

    /** Proves a crash before the booking is recorded sends nothing: the message is never sent for a booking that might not exist. */
    @ParameterizedTest
    @EnumSource(value = CrashHook.Point.class, names = {"BEFORE_SEAT_HOLD", "AFTER_SEAT_HOLD", "AFTER_PAYMENT_HOLD", "AFTER_CAPTURE_BEFORE_SAVE", "AFTER_CAPTURE", "AFTER_CONFIRM_BEFORE_SAVE"})
    void nothingIsDeliveredBeforeTheBookingIsRecorded(CrashHook.Point point) {
        Rig r = new Rig();
        r.crashOnce(point);
        assertThrows(Rig.SimulatedCrash.class, () -> r.checkout.checkout("c1", Rig.request("ann", "A1")));
        r.notifications.runDue();
        assertEquals(0, r.delivered.size());
        assertTrue(r.notifications.find("n1").isEmpty());
    }

    /** Proves the rule for "money taken but confirm failed": if the hold ran out after the capture, the capture is reversed (refund), the seats are free and the books balance. */
    @Test
    void capturedButHoldExpiredIsRefunded() {
        Rig r = new Rig();
        r.crashOnce(CrashHook.Point.AFTER_CAPTURE);
        assertThrows(Rig.SimulatedCrash.class, () -> r.checkout.checkout("c1", Rig.request("ann", "A1")));
        r.clock.advance(Duration.ofMinutes(6));
        CheckoutResult res = r.checkout.checkout("c1", Rig.request("ann", "A1"));
        assertEquals(State.REFUNDED, res.state());
        assertEquals(0, r.ledger.naturalBalance("venue"));
        assertEquals(Rig.WALLET, r.ledger.naturalBalance(CheckoutService.walletOf("ann")));
        assertEquals(SeatState.AVAILABLE, r.bookings.seatState("s1", "A1"));
        assertTrue(r.bookings.refundsNeeded().isEmpty());
        assertEquals(0, r.ledger.trialBalance("USD"));
        assertEquals(0, r.delivered.size());
    }

    /** Proves that a capture which reached the ledger but was never written down is found again by the retry and not charged a second time, even after the hold expired (refund path). */
    @Test
    void captureNotSavedThenExpiredIsRefundedNotRecaptured() {
        Rig r = new Rig();
        r.crashOnce(CrashHook.Point.AFTER_CAPTURE_BEFORE_SAVE);
        assertThrows(Rig.SimulatedCrash.class, () -> r.checkout.checkout("c1", Rig.request("ann", "A1")));
        r.clock.advance(Duration.ofMinutes(6));
        assertEquals(State.REFUNDED, r.checkout.checkout("c1", Rig.request("ann", "A1")).state());
        assertEquals(1, r.captures());
        assertEquals(Rig.WALLET, r.ledger.naturalBalance(CheckoutService.walletOf("ann")));
    }

    /** Proves a crash after the payment hold followed by expiry cancels on the buyer's own retry: payment hold voided, seats freed, nothing charged. */
    @Test
    void retryAfterExpiryOfUncapturedCheckoutCancels() {
        Rig r = new Rig();
        r.crashOnce(CrashHook.Point.AFTER_PAYMENT_HOLD);
        assertThrows(Rig.SimulatedCrash.class, () -> r.checkout.checkout("c1", Rig.request("ann", "A1")));
        r.clock.advance(Duration.ofMinutes(5));
        assertEquals(State.CANCELLED, r.checkout.checkout("c1", Rig.request("ann", "A1")).state());
        assertEquals(Rig.WALLET, r.available("ann"));
        assertEquals(0, r.captures());
    }

    /** Proves a finished checkout answers with replay true and does nothing more, however often it is retried. */
    @Test
    void finishedCheckoutReplaysTheSameAnswer() {
        Rig r = new Rig();
        CheckoutResult first = r.checkout.checkout("c1", Rig.request("ann", "A1"));
        CheckoutResult again = r.checkout.checkout("c1", Rig.request("ann", "A1"));
        assertFalse(first.replay());
        assertTrue(again.replay());
        assertEquals(first.bookingId(), again.bookingId());
        assertEquals(1, r.captures());
        assertEquals(Rig.WALLET - Rig.PRICE, r.available("ann"));
    }

    /** Proves that a business failure is also replayed: a rejected key stays rejected, even after the seat becomes free. */
    @Test
    void rejectedCheckoutStaysRejectedOnReplay() {
        Rig r = new Rig();
        var other = r.bookings.hold("s1", java.util.List.of("A1"));
        CheckoutResult first = r.checkout.checkout("c1", Rig.request("ann", "A1"));
        assertEquals(State.REJECTED, first.state());
        assertFalse(first.replay());
        r.bookings.release(other.holdId());
        CheckoutResult again = r.checkout.checkout("c1", Rig.request("ann", "A1"));
        assertEquals(State.REJECTED, again.state());
        assertTrue(again.replay());
        assertEquals(first.reason(), again.reason());
        assertTrue(first.reason().contains("A1"));
    }

    /** Proves a key reused for a different purchase is refused instead of silently returning the old answer. */
    @Test
    void sameKeyDifferentRequestConflicts() {
        Rig r = new Rig();
        r.checkout.checkout("c1", Rig.request("ann", "A1"));
        assertThrows(CheckoutKeyConflictException.class, () -> r.checkout.checkout("c1", Rig.request("ann", "A2")));
        assertThrows(IllegalArgumentException.class, () -> r.checkout.checkout(" ", Rig.request("ann", "A2")));
    }

    /** Proves the sweep rolls a stuck, expired checkout back without the buyer retrying, and leaves a live one alone. */
    @Test
    void sweepLeavesLiveCheckoutsAlone() {
        Rig r = new Rig();
        r.crashOnce(CrashHook.Point.AFTER_SEAT_HOLD);
        assertThrows(Rig.SimulatedCrash.class, () -> r.checkout.checkout("c1", Rig.request("ann", "A1")));
        assertEquals(0, r.checkout.sweep());
        assertEquals(State.SEATS_HELD, r.checkout.stateOf("c1"));
        r.clock.advance(Duration.ofMinutes(5));
        assertEquals(1, r.checkout.sweep());
        assertEquals(State.CANCELLED, r.checkout.stateOf("c1"));
    }
}
