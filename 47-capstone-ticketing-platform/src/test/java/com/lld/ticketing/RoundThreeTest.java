package com.lld.ticketing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lld.booking.SeatState;
import com.lld.notify.Status;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Round 3 (30 to 45 min): the confirmation message, and two buyers racing for the last seat. */
class RoundThreeTest {

    /** Proves the buyer is told once, with a key built from the checkout key, and that a second checkout gets a different message. */
    @Test
    void notificationKeyIsDerivedFromTheCheckoutId() {
        Rig r = new Rig();
        r.checkout.checkout("c1", Rig.request("ann", "A1"));
        r.checkout.checkout("c2", Rig.request("bob", "A2"));
        r.notifications.runDue();
        assertEquals(2, r.delivered.size());
        assertTrue(r.delivered.get(0).startsWith("ann@x.com|Booked b1 seats A1"));
        assertEquals(2, r.deliveredKeys.stream().distinct().count());
        assertEquals(r.notifications.submit("confirm:c1", new com.lld.notify.NotificationRequest("ann", com.lld.notify.ChannelType.EMAIL, CheckoutService.TEMPLATE,
                java.util.Map.of("booking", "b1", "seats", "A1"), null)).id(), r.checkout.checkout("c1", Rig.request("ann", "A1")).notificationId());
    }

    /** Proves a crash after the notification service accepted the message does not make a second message: the retry resubmits under the same key and gets the same id. */
    @Test
    void retryAfterSubmitCrashDoesNotNotifyTwice() {
        Rig r = new Rig();
        r.crashOnce(CrashHook.Point.AFTER_NOTIFY_SUBMIT);
        assertThrows(Rig.SimulatedCrash.class, () -> r.checkout.checkout("c1", Rig.request("ann", "A1")));
        CheckoutResult res = r.checkout.checkout("c1", Rig.request("ann", "A1"));
        r.notifications.runDue();
        assertEquals("n1", res.notificationId());
        assertEquals(1, r.delivered.size());
    }

    /** Proves a crash right after the booking is recorded still leaves the booking CONFIRMED and the note waiting in the outbox, and flushOutbox sends it exactly once. */
    @Test
    void outboxSurvivesACrashAfterConfirm() {
        Rig r = new Rig();
        r.crashOnce(CrashHook.Point.BEFORE_NOTIFY);
        assertThrows(Rig.SimulatedCrash.class, () -> r.checkout.checkout("c1", Rig.request("ann", "A1")));
        assertEquals(State.CONFIRMED, r.checkout.stateOf("c1"));
        assertEquals(0, r.notifications.runDue());
        assertEquals(1, r.checkout.flushOutbox());
        assertEquals(0, r.checkout.flushOutbox());
        r.notifications.runDue();
        assertEquals(1, r.delivered.size());
    }

    /** Proves a provider outage never rolls back a sale: the booking and the money stay, and the message retries then lands in the dead letter queue. */
    @Test
    void sendFailureNeverRollsBackTheBooking() {
        Rig r = new Rig();
        r.transportDown = true;
        CheckoutResult res = r.checkout.checkout("c1", Rig.request("ann", "A1"));
        assertEquals(State.CONFIRMED, res.state());
        for (int i = 0; i < 4; i++) {
            r.notifications.runDue();
            r.clock.advance(Duration.ofSeconds(30));
        }
        assertEquals(Status.DEAD, r.notifications.find(res.notificationId()).orElseThrow().status());
        assertEquals(State.CONFIRMED, r.checkout.stateOf("c1"));
        assertEquals(SeatState.BOOKED, r.bookings.seatState("s1", "A1"));
        assertEquals(Rig.PRICE, r.ledger.naturalBalance("venue"));
    }

    /** Proves a message the notification service rejects (unknown user) leaves the booking intact and the note waiting, and it goes out once the user exists. */
    @Test
    void rejectedMessageStaysInTheOutbox() {
        Rig r = new Rig();
        r.prefs.remove("ann");
        CheckoutResult res = r.checkout.checkout("c1", Rig.request("ann", "A1"));
        assertEquals(State.CONFIRMED, res.state());
        assertNull(res.notificationId());
        assertTrue(res.reason().startsWith("notification waiting"));
        assertEquals(0, r.checkout.flushOutbox());
        r.prefs.put("ann", new com.lld.notify.UserPreferences(java.time.ZoneOffset.UTC, java.util.Map.of(com.lld.notify.ChannelType.EMAIL, "ann@x.com"),
                java.util.Set.of(com.lld.notify.ChannelType.EMAIL), java.util.Optional.empty()));
        assertEquals(1, r.checkout.flushOutbox());
        r.notifications.runDue();
        assertEquals(1, r.delivered.size());
        CheckoutResult again = r.checkout.checkout("c1", Rig.request("ann", "A1"));
        assertTrue(again.replay());
        assertEquals("", again.reason());
    }

    /** Proves the race with a forced interleaving: ann is frozen right after her payment hold, bob asks for the same seat and is rejected, then ann finishes. Exactly one sale. */
    @Test
    void raceForTheLastSeatHasOneWinner() throws Exception {
        Rig r = new Rig();
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        r.behaviour = (p, k) -> {
            if (p == CrashHook.Point.AFTER_PAYMENT_HOLD && k.equals("ca")) {
                reached.countDown();
                await(go);
            }
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<CheckoutResult> ann = pool.submit(() -> r.checkout.checkout("ca", Rig.request("ann", "A1")));
            assertTrue(reached.await(5, TimeUnit.SECONDS));
            CheckoutResult bob = r.checkout.checkout("cb", Rig.request("bob", "A1"));
            assertEquals(State.REJECTED, bob.state());
            go.countDown();
            assertEquals(State.CONFIRMED, ann.get(5, TimeUnit.SECONDS).state());
        } finally {
            go.countDown();
            pool.shutdownNow();
        }
        assertEquals(1, r.captures());
        assertEquals(Rig.WALLET, r.available("bob"));
        assertEquals(Rig.PRICE, r.ledger.naturalBalance("venue"));
    }

    /** Proves the loser who already holds money is released: ann reserves and stalls, her hold expires, bob wins the seat, and when ann returns she is cancelled and never charged. */
    @Test
    void staleLoserPaymentHoldIsReleased() {
        Rig r = new Rig();
        r.crashOnce(CrashHook.Point.AFTER_PAYMENT_HOLD);
        assertThrows(Rig.SimulatedCrash.class, () -> r.checkout.checkout("ca", Rig.request("ann", "A1")));
        r.clock.advance(Duration.ofMinutes(5));
        assertEquals(State.CONFIRMED, r.checkout.checkout("cb", Rig.request("bob", "A1")).state());
        CheckoutResult ann = r.checkout.checkout("ca", Rig.request("ann", "A1"));
        assertEquals(State.CANCELLED, ann.state());
        assertEquals(Rig.WALLET, r.available("ann"));
        assertEquals(Rig.WALLET, r.ledger.naturalBalance(CheckoutService.walletOf("ann")));
        assertEquals(SeatState.BOOKED, r.bookings.seatState("s1", "A1"));
        assertEquals(1, r.captures());
    }

    /** Waits for a latch without a timeout loop; a test failure elsewhere opens it in finally, so this never hangs. */
    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
