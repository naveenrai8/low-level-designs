package com.lld.ticketing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lld.booking.SeatState;
import com.lld.ledger.Entry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Round 2 follow-ups: the edges an interviewer reaches after the basic crash matrix (time limits, forgotten holds, rollback crashes, busy keys, bad input). */
class RoundTwoHardeningTest {

    private static final Duration SEAT_HOLD = Duration.ofMinutes(5);
    private static final Duration GRACE = Duration.ofMinutes(10);

    /** Crashes ann's checkout A1 at the given point, with the standard request. */
    private static void crashAt(Rig r, String key, CrashHook.Point point, String seat) {
        r.crashOnce(point);
        assertThrows(Rig.SimulatedCrash.class, () -> r.checkout.checkout(key, Rig.request("ann", seat)));
    }

    /** Proves the payment hold has a time limit: left alone it frees itself once seat hold plus grace has passed, with no call from anyone. */
    @Test
    void paymentHoldFreesItselfAfterItsLimit() {
        Rig r = new Rig();
        crashAt(r, "c1", CrashHook.Point.AFTER_PAYMENT_HOLD, "A1");
        r.clock.advance(SEAT_HOLD.plus(GRACE));
        assertEquals(Rig.WALLET, r.available("ann"));
    }

    /** Proves a rollback still works when the ledger already expired the payment hold itself: the void is refused as expired, which counts as done, and the checkout ends CANCELLED with the seats free. */
    @Test
    void rollbackToleratesAPaymentHoldThatAlreadyExpired() {
        Rig r = new Rig();
        crashAt(r, "c1", CrashHook.Point.AFTER_PAYMENT_HOLD, "A1");
        r.clock.advance(Duration.ofMinutes(16));
        assertEquals(1, r.checkout.sweep());
        assertEquals(State.CANCELLED, r.checkout.stateOf("c1"));
        assertEquals(Rig.WALLET, r.available("ann"));
        assertEquals(SeatState.AVAILABLE, r.bookings.seatState("s1", "A1"));
        assertEquals(0, r.checkout.sweepFailures());
    }

    /** Proves the exact edge of that limit: still reserved one nanosecond before, free exactly at the limit. */
    @Test
    void paymentHoldLimitIsExactlySeatHoldPlusGrace() {
        Rig r = new Rig();
        crashAt(r, "c1", CrashHook.Point.AFTER_PAYMENT_HOLD, "A1");
        r.clock.advance(SEAT_HOLD.plus(GRACE).minusNanos(1));
        assertEquals(Rig.WALLET - Rig.PRICE, r.available("ann"));
        r.clock.advance(Duration.ofNanos(1));
        assertEquals(Rig.WALLET, r.available("ann"));
    }

    /** Proves a checkout whose seat hold ran out before any payment hold is cancelled without ever reserving money, and says why. */
    @Test
    void expiredBeforePaymentHoldNeverReservesMoney() {
        Rig r = new Rig();
        crashAt(r, "c1", CrashHook.Point.AFTER_SEAT_HOLD, "A1");
        r.clock.advance(SEAT_HOLD);
        assertEquals(1, r.checkout.sweep());
        assertEquals(State.CANCELLED, r.checkout.stateOf("c1"));
        assertEquals(Rig.WALLET, r.available("ann"));
        CheckoutResult res = r.checkout.checkout("c1", Rig.request("ann", "A1"));
        assertEquals("seat hold expired before the payment hold", res.reason());
    }

    /** Proves the reasons are reported: a taken seat, an unaffordable price, and an expiry before the capture. */
    @Test
    void endStatesCarryTheirReason() {
        Rig r = new Rig();
        r.bookings.hold("s1", List.of("A1"));
        assertTrue(r.checkout.checkout("c1", Rig.request("ann", "A1")).reason().contains("A1"));
        CheckoutResult declined = r.checkout.checkout("c2", new CheckoutRequest("ann", "s1", List.of("A2"), Rig.WALLET + 1));
        assertEquals("insufficient funds", declined.reason());
        crashAt(r, "c3", CrashHook.Point.AFTER_PAYMENT_HOLD, "A3");
        r.clock.advance(SEAT_HOLD);
        assertEquals("seat hold expired before the capture", r.checkout.checkout("c3", Rig.request("ann", "A3")).reason());
    }

    /** Proves the checkout confirms with the capture entry id as the payment reference, so the booking can be traced to the ledger entry. */
    @Test
    void bookingCarriesTheCaptureEntryAsPaymentRef() {
        Rig r = new Rig();
        CheckoutResult res = r.checkout.checkout("c1", Rig.request("ann", "A1"));
        Entry capture = r.ledger.journal().stream().filter(e -> e.postings().stream().anyMatch(p -> p.accountId().equals("venue"))).findFirst().orElseThrow();
        assertTrue(r.bookings.confirm("h1", "c1", capture.id()).replay());
        assertEquals(res.bookingId(), r.bookings.confirm("h1", "c1", capture.id()).booking().bookingId());
    }

    /** Proves the hard case: the sweep still works after Topic 31 forgot the hold (66 minutes), cancelling the checkout and freeing the money. */
    @Test
    void sweepWorksAfterTheSeatHoldWasForgotten() {
        Rig r = new Rig();
        crashAt(r, "c1", CrashHook.Point.AFTER_SEAT_HOLD, "A1");
        r.clock.advance(Duration.ofMinutes(66));
        assertEquals(1, r.checkout.sweep());
        assertEquals(State.CANCELLED, r.checkout.stateOf("c1"));
        assertEquals(Rig.WALLET, r.available("ann"));
    }

    /** Proves one record that keeps failing does not stop the sweep from finishing the others, and is counted so someone can alert on it. */
    @Test
    void oneBadRecordDoesNotBlockTheSweep() {
        Rig r = new Rig();
        crashAt(r, "c1", CrashHook.Point.AFTER_PAYMENT_HOLD, "A1");
        crashAt(r, "c2", CrashHook.Point.AFTER_PAYMENT_HOLD, "A2");
        r.clock.advance(SEAT_HOLD);
        r.behaviour = (p, k) -> {
            if (p == CrashHook.Point.AFTER_VOID && k.equals("c1")) {
                throw new Rig.SimulatedCrash(p);
            }
        };
        assertEquals(1, r.checkout.sweep());
        assertEquals(1, r.checkout.sweepFailures());
        assertEquals(State.CANCELLED, r.checkout.stateOf("c2"));
        assertFalse(r.checkout.stateOf("c1").isTerminal());
        r.behaviour = CrashHook.NONE;
        assertEquals(1, r.checkout.sweep());
        assertEquals(State.CANCELLED, r.checkout.stateOf("c1"));
        assertEquals(Rig.WALLET, r.available("ann"));
    }

    /** Proves a sweep over a record that has not even held seats yet skips it quietly instead of failing. */
    @Test
    void sweepIgnoresARecordThatNeverStarted() {
        Rig r = new Rig();
        crashAt(r, "c1", CrashHook.Point.BEFORE_SEAT_HOLD, "A1");
        assertEquals(0, r.checkout.sweep());
        assertEquals(0, r.checkout.sweepFailures());
        assertEquals(State.CONFIRMED, r.checkout.checkout("c1", Rig.request("ann", "A1")).state());
    }

    /** Proves a late retry never refunds a sale it cannot disprove: with the hold forgotten, the money stays, the seat stays booked and the checkout is flagged NEEDS_REVIEW. */
    @Test
    void forgottenHoldIsFlaggedNotRefunded() {
        Rig r = new Rig();
        crashAt(r, "c1", CrashHook.Point.AFTER_CONFIRM_BEFORE_SAVE, "A1");
        r.clock.advance(Duration.ofHours(25));
        CheckoutResult res = r.checkout.checkout("c1", Rig.request("ann", "A1"));
        assertEquals(State.NEEDS_REVIEW, res.state());
        assertTrue(res.reason().startsWith("needs review"));
        assertEquals(Rig.PRICE, r.ledger.naturalBalance("venue"));
        assertEquals(SeatState.BOOKED, r.bookings.seatState("s1", "A1"));
        assertEquals(0, r.ledger.trialBalance("USD"));
    }

    /** Proves the other unprovable case: the hold was booked under a different key, so the checkout leaves the money alone and flags it. */
    @Test
    void bookedUnderAnotherKeyIsFlaggedNotRefunded() {
        Rig r = new Rig();
        crashAt(r, "c1", CrashHook.Point.AFTER_CAPTURE, "A1");
        r.bookings.confirm("h1", "other-key", "someone-else");
        assertEquals(State.NEEDS_REVIEW, r.checkout.checkout("c1", Rig.request("ann", "A1")).state());
        assertEquals(Rig.PRICE, r.ledger.naturalBalance("venue"));
        assertEquals(1, r.captures());
    }

    /** Proves a crash right after the capture was reversed still ends REFUNDED on retry, with the wallet whole and the ledger balanced. */
    @Test
    void crashAfterReverseStillRefundsOnce() {
        Rig r = new Rig();
        crashAt(r, "c1", CrashHook.Point.AFTER_CAPTURE, "A1");
        r.clock.advance(SEAT_HOLD);
        r.crashOnce(CrashHook.Point.AFTER_REVERSE);
        assertThrows(Rig.SimulatedCrash.class, () -> r.checkout.checkout("c1", Rig.request("ann", "A1")));
        assertEquals(State.REFUNDED, r.checkout.checkout("c1", Rig.request("ann", "A1")).state());
        assertEquals(Rig.WALLET, r.ledger.naturalBalance(CheckoutService.walletOf("ann")));
        assertEquals(0, r.ledger.naturalBalance("venue"));
        assertEquals(0, r.ledger.trialBalance("USD"));
    }

    /** Proves a crash after the payment hold was voided but before the seats were released still ends CANCELLED on retry, with the seats free. */
    @Test
    void crashAfterVoidStillCancelsOnRetry() {
        Rig r = new Rig();
        crashAt(r, "c1", CrashHook.Point.AFTER_PAYMENT_HOLD, "A1");
        r.clock.advance(SEAT_HOLD);
        r.crashOnce(CrashHook.Point.AFTER_VOID);
        assertThrows(Rig.SimulatedCrash.class, () -> r.checkout.checkout("c1", Rig.request("ann", "A1")));
        assertEquals(State.CANCELLED, r.checkout.checkout("c1", Rig.request("ann", "A1")).state());
        assertEquals(SeatState.AVAILABLE, r.bookings.seatState("s1", "A1"));
        assertEquals(Rig.WALLET, r.available("ann"));
    }

    /** Proves the known gap, honestly: a crash between the ledger's authorize and saving its id makes the retry reserve twice, so a buyer with exactly the price is wrongly DECLINED. The real fix is a keyed authorize in Topic 32. */
    @Test
    void knownGapDoubleAuthorizeFalselyDeclinesAFullWallet() {
        Rig r = new Rig();
        CheckoutRequest all = new CheckoutRequest("ann", "s1", List.of("A1"), Rig.WALLET);
        r.crashOnce(CrashHook.Point.AFTER_AUTHORIZE_BEFORE_SAVE);
        assertThrows(Rig.SimulatedCrash.class, () -> r.checkout.checkout("c1", all));
        assertEquals(State.DECLINED, r.checkout.checkout("c1", all).state());
        assertEquals(SeatState.AVAILABLE, r.bookings.seatState("s1", "A1"));
        r.clock.advance(SEAT_HOLD.plus(GRACE));
        assertEquals(Rig.WALLET, r.available("ann"));
    }

    /** Proves a second call with the same key while the first is still running does not wait: it gets the 409 error, and sweep, flush and state reads all return at once. */
    @Test
    void busyKeyAnswersInProgressWithoutBlocking() throws Exception {
        Rig r = new Rig();
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        r.behaviour = (p, k) -> {
            if (p == CrashHook.Point.AFTER_SEAT_HOLD) {
                reached.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<CheckoutResult> first = pool.submit(() -> r.checkout.checkout("c1", Rig.request("ann", "A1")));
            assertTrue(reached.await(5, TimeUnit.SECONDS));
            assertThrows(CheckoutInProgressException.class, () -> r.checkout.checkout("c1", Rig.request("ann", "A1")));
            assertThrows(CheckoutKeyConflictException.class, () -> r.checkout.checkout("c1", Rig.request("ann", "A2")));
            r.clock.advance(Duration.ofHours(2));
            assertEquals(0, r.checkout.sweep());
            assertEquals(0, r.checkout.flushOutbox());
            assertEquals(0, r.checkout.purge());
            assertEquals(State.SEATS_HELD, r.checkout.stateOf("c1"));
            go.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            go.countDown();
            pool.shutdownNow();
        }
    }

    /** Proves only "not enough money" becomes DECLINED: a buyer with no wallet is a real error that is thrown, and the checkout stays resumable instead of being falsely declined. */
    @Test
    void otherLedgerErrorsAreNotDeclines() {
        Rig r = new Rig();
        CheckoutRequest ghost = new CheckoutRequest("zed", "s1", List.of("A1"), Rig.PRICE);
        com.lld.ledger.LedgerException e = assertThrows(com.lld.ledger.LedgerException.class, () -> r.checkout.checkout("c1", ghost));
        assertEquals(com.lld.ledger.LedgerException.Reason.UNKNOWN_ACCOUNT, e.reason());
        assertEquals(State.SEATS_HELD, r.checkout.stateOf("c1"));
    }

    /** Proves bad input is refused up front: a zero price, repeated seats, and zero settings. */
    @Test
    void badInputIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new CheckoutRequest("ann", "s1", List.of("A1"), 0));
        assertThrows(IllegalArgumentException.class, () -> new CheckoutRequest("ann", "s1", List.of("A1", "A1"), 1));
        assertThrows(IllegalArgumentException.class, () -> new TicketingConfig("venue", Duration.ZERO, Duration.ofHours(1)));
        assertThrows(IllegalArgumentException.class, () -> new TicketingConfig("venue", Duration.ofMinutes(1), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new TicketingConfig(" ", Duration.ofMinutes(1), Duration.ofMinutes(1)));
    }

    /** Proves the same seats in a different order are the same request, so a retry that lists them differently is not a conflict. */
    @Test
    void seatOrderDoesNotMatter() {
        Rig r = new Rig();
        assertEquals(Rig.request("ann", "A1", "A2"), Rig.request("ann", "A2", "A1"));
        r.checkout.checkout("c1", Rig.request("ann", "A1", "A2"));
        assertTrue(r.checkout.checkout("c1", Rig.request("ann", "A2", "A1")).replay());
    }

    /** Proves finished checkouts are forgotten only after the key retention, and a checkout with a waiting message is never forgotten. */
    @Test
    void purgeForgetsOnlyOldFinishedCheckouts() {
        Rig r = new Rig();
        r.checkout.checkout("c1", Rig.request("ann", "A1"));
        r.prefs.remove("bob");
        r.checkout.checkout("c2", Rig.request("bob", "A2"));
        assertEquals(0, r.checkout.purge());
        r.clock.advance(Duration.ofHours(24));
        assertEquals(1, r.checkout.purge());
        assertNull(r.checkout.stateOf("c1"));
        assertEquals(State.CONFIRMED, r.checkout.stateOf("c2"));
    }
}
