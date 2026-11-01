package com.lld.ticketing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lld.booking.SeatState;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The whole evening in virtual time: ann and bob race for the last seat, ann wins and survives a crash and retries, bob is released, the books balance. */
class EndToEndTest {

    /** Proves the full scenario: one sale, one message, no charge for the loser, retries change nothing, the venue holds exactly the captured amount and the ledger sums to zero. */
    @Test
    void raceCrashRetryAndBalancedBooks() {
        Rig r = new Rig();
        // ann takes the seat and her process dies right after the money moved.
        r.crashOnce(CrashHook.Point.AFTER_CAPTURE);
        assertThrows(Rig.SimulatedCrash.class, () -> r.checkout.checkout("ca", Rig.request("ann", "A3")));
        // bob arrives while ann's seat is still held: he loses, and nothing of his is reserved.
        r.clock.advance(Duration.ofMinutes(1));
        CheckoutResult bob = r.checkout.checkout("cb", Rig.request("bob", "A3"));
        assertEquals(State.REJECTED, bob.state());
        assertEquals(Rig.WALLET, r.available("bob"));
        // ann retries: same key, confirm and notify.
        CheckoutResult ann = r.checkout.checkout("ca", Rig.request("ann", "A3"));
        assertEquals(State.CONFIRMED, ann.state());
        // more retries, a sweep, a flush and a notification run change nothing.
        r.clock.advance(Duration.ofMinutes(10));
        assertTrue(r.checkout.checkout("ca", Rig.request("ann", "A3")).replay());
        assertEquals(0, r.checkout.sweep());
        assertEquals(0, r.checkout.flushOutbox());
        r.notifications.runDue();
        r.notifications.runDue();
        // the outcome.
        assertEquals(1, r.delivered.size());
        assertTrue(r.delivered.get(0).startsWith("ann@x.com|"));
        assertEquals(1, r.captures());
        assertEquals(SeatState.BOOKED, r.bookings.seatState("s1", "A3"));
        assertEquals(Rig.PRICE, r.ledger.naturalBalance("venue"));
        assertEquals(Rig.WALLET - Rig.PRICE, r.ledger.naturalBalance(CheckoutService.walletOf("ann")));
        assertEquals(Rig.WALLET, r.ledger.naturalBalance(CheckoutService.walletOf("bob")));
        Map<String, Long> rebuilt = r.ledger.rebuild();
        assertEquals(0L, rebuilt.values().stream().mapToLong(Long::longValue).sum());
        assertEquals(0, r.ledger.trialBalance("USD"));
    }
}
