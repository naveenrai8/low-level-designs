package com.lld.ticketing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lld.booking.SeatState;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Round 1 (0 to 15 min): hold, pay, confirm, and the two ways out that are not a sale. */
class RoundOneTest {

    /** Proves the happy path: the seats are booked, the venue got exactly the price, the wallet lost exactly the price and the ledger sums to zero. */
    @Test
    void holdPayConfirmMovesExactlyThePrice() {
        Rig r = new Rig();
        CheckoutResult res = r.checkout.checkout("c1", Rig.request("ann", "A1", "A2"));
        assertEquals(State.CONFIRMED, res.state());
        assertNotNull(res.bookingId());
        assertEquals(SeatState.BOOKED, r.bookings.seatState("s1", "A1"));
        assertEquals(Rig.PRICE, r.ledger.naturalBalance("venue"));
        assertEquals(Rig.WALLET - Rig.PRICE, r.ledger.naturalBalance(CheckoutService.walletOf("ann")));
        assertEquals(Rig.WALLET - Rig.PRICE, r.available("ann"));
        assertEquals(0, r.ledger.trialBalance("USD"));
    }

    /** Proves a seat that is already held makes the checkout REJECTED, with no payment hold and no money moved. */
    @Test
    void takenSeatIsRejectedAndNothingIsReserved() {
        Rig r = new Rig();
        r.bookings.hold("s1", List.of("A1"));
        CheckoutResult res = r.checkout.checkout("c1", Rig.request("ann", "A1", "A2"));
        assertEquals(State.REJECTED, res.state());
        assertEquals(Rig.WALLET, r.available("ann"));
        assertEquals(SeatState.AVAILABLE, r.bookings.seatState("s1", "A2"));
        assertEquals(0, r.captures());
    }

    /** Proves a buyer who cannot afford the price is DECLINED and the seats go back on sale at once. */
    @Test
    void insufficientFundsDeclinesAndReleasesSeats() {
        Rig r = new Rig();
        CheckoutResult res = r.checkout.checkout("c1", new CheckoutRequest("ann", "s1", List.of("A1"), Rig.WALLET + 1));
        assertEquals(State.DECLINED, res.state());
        assertEquals(SeatState.AVAILABLE, r.bookings.seatState("s1", "A1"));
        assertEquals(0, r.ledger.naturalBalance("venue"));
    }

    /** Proves the key point of round 1: when the seat hold expires, the sweep releases the payment hold too, so the buyer is not charged and the money is spendable again. */
    @Test
    void expiredSeatHoldReleasesThePaymentHold() {
        Rig r = new Rig();
        r.crashOnce(CrashHook.Point.AFTER_PAYMENT_HOLD);
        assertThrows(Rig.SimulatedCrash.class, () -> r.checkout.checkout("c1", Rig.request("ann", "A1")));
        assertEquals(Rig.WALLET - Rig.PRICE, r.available("ann"));
        assertEquals(0, r.checkout.sweep());
        r.clock.advance(Duration.ofMinutes(5));
        assertEquals(1, r.checkout.sweep());
        assertEquals(State.CANCELLED, r.checkout.stateOf("c1"));
        assertEquals(Rig.WALLET, r.available("ann"));
        assertEquals(SeatState.AVAILABLE, r.bookings.seatState("s1", "A1"));
        assertEquals(0, r.captures());
    }

    /** Proves the payment hold outlives the seat hold, so the money can never be freed before the seats are. */
    @Test
    void paymentHoldOutlivesSeatHold() {
        Rig r = new Rig();
        r.crashOnce(CrashHook.Point.AFTER_PAYMENT_HOLD);
        assertThrows(Rig.SimulatedCrash.class, () -> r.checkout.checkout("c1", Rig.request("ann", "A1")));
        r.clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertTrue(r.available("ann") < Rig.WALLET);
    }
}
