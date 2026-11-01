package com.lld.booking;

import static com.lld.booking.Support.l;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** F2: confirm with an idempotency key and a payment reference. */
class IdempotentConfirmTest {

    private final TestClock clock = new TestClock();
    private final SeatHoldBookingService svc = Support.service(clock, 4);

    /** Proves the same key and the same request return the same booking, flagged as a replay, and draw only one booking id. */
    @Test
    void replayReturnsTheSameBooking() {
        Hold h = svc.hold("s", l("A1", "A2"));
        ConfirmResult first = svc.confirm(h.holdId(), "k", "pay-1");
        ConfirmResult again = svc.confirm(h.holdId(), "k", "pay-1");
        assertFalse(first.replay());
        assertTrue(again.replay());
        assertEquals(first.booking(), again.booking());
        assertEquals("b-1", again.booking().bookingId());
    }

    /** Proves the same key with a different payment reference or a different hold is a conflict, and nothing changes. */
    @Test
    void sameKeyDifferentRequestIsAConflict() {
        Hold h = svc.hold("s", l("A1"));
        Hold other = svc.hold("s", l("A2"));
        svc.confirm(h.holdId(), "k", "pay-1");
        assertThrows(IdempotencyConflictException.class, () -> svc.confirm(h.holdId(), "k", "pay-2"));
        assertThrows(IdempotencyConflictException.class, () -> svc.confirm(other.holdId(), "k", "pay-1"));
        assertEquals(SeatState.HELD, svc.seatState("s", "A2"));
    }

    /** Proves a replay still works after the hold's own expiry time passed, because the booking is final. */
    @Test
    void replayWorksAfterTheHoldWouldHaveExpired() {
        Hold h = svc.hold("s", l("A1"));
        Booking b = svc.confirm(h.holdId(), "k", "p").booking();
        clock.advance(Duration.ofMinutes(10));
        assertEquals(b, svc.confirm(h.holdId(), "k", "p").booking());
    }

    /** Proves a late payment callback is recorded once as "needs refund", survives retries, and can be marked done. */
    @Test
    void lateCallbackIsRecordedForRefund() {
        Hold h = svc.hold("s", l("A1"));
        clock.advance(Duration.ofMinutes(5));
        assertThrows(HoldExpiredException.class, () -> svc.confirm(h.holdId(), "k", "pay-9"));
        assertThrows(HoldExpiredException.class, () -> svc.confirm(h.holdId(), "k", "pay-9"));
        assertEquals(1, svc.refundsNeeded().size());
        RefundNeeded r = svc.refundsNeeded().get(0);
        assertEquals("pay-9", r.paymentRef());
        assertEquals(h.holdId(), r.holdId());
        assertEquals(clock.instant(), r.recordedAt());
        assertEquals(SeatState.AVAILABLE, svc.seatState("s", "A1"));
        svc.refundDone("k");
        assertTrue(svc.refundsNeeded().isEmpty());
    }

    /** Proves a blank key is a caller bug and confirming does not need the key to be fresh across different holds. */
    @Test
    void keyIsRequired() {
        Hold h = svc.hold("s", l("A1"));
        assertThrows(IllegalArgumentException.class, () -> svc.confirm(h.holdId(), " ", "p"));
        assertThrows(IllegalArgumentException.class, () -> svc.confirm(h.holdId(), null, "p"));
    }

    /** Proves the key store is bounded: keys vanish at exactly the TTL, a forgotten key is no longer a replay, and the store cleans itself on access. */
    @Test
    void idempotencyKeysExpireAtTheTtl() {
        SeatHoldBookingService s = svc;
        Hold h = s.hold("s", l("A1"));
        s.confirm(h.holdId(), "k", "p");
        assertEquals(1, s.idempotencyKeys());
        clock.advance(Duration.ofHours(1).minusNanos(1));
        assertTrue(s.confirm(h.holdId(), "k", "p").replay());
        clock.advance(Duration.ofNanos(1));
        // the hold record itself is forgotten after 30 minutes, so 404 rather than a replay
        assertThrows(HoldNotFoundException.class, () -> s.confirm(h.holdId(), "k", "p"));
        assertEquals(0, s.idempotencyKeys());
    }

    /** Proves memory stays bounded without any sweep call: 1000 keys older than the TTL are gone after the next call. */
    @Test
    void storeDoesNotGrowWithoutLimit() {
        SeatHoldBookingService s = new SeatHoldBookingService(clock, Support.CONFIG, Support.ids("h-"), Support.ids("b-"));
        s.addShow("big", Support.seats(1000));
        for (int i = 1; i <= 1000; i++) {
            Hold h = s.hold("big", l("A" + i));
            s.confirm(h.holdId(), "key" + i, "p" + i);
        }
        assertEquals(1000, s.idempotencyKeys());
        clock.advance(Duration.ofHours(2));
        assertThrows(HoldNotFoundException.class, () -> s.confirm("h-none", "fresh", "p"));
        assertEquals(0, s.idempotencyKeys());
    }

    /** Proves many threads confirming the same hold with the same key produce exactly one booking: one first call, all others replays. */
    @Test
    void concurrentSameKeyConfirmsMakeOneBooking() throws Exception {
        Hold h = svc.hold("s", l("A1", "A2"));
        int threads = 16;
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger firsts = new AtomicInteger();
        AtomicInteger replays = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        java.util.List<Future<Booking>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            fs.add(pool.submit(() -> {
                gate.await();
                ConfirmResult r = svc.confirm(h.holdId(), "k", "p");
                (r.replay() ? replays : firsts).incrementAndGet();
                return r.booking();
            }));
        }
        gate.countDown();
        Booking expected = fs.get(0).get(10, TimeUnit.SECONDS);
        for (Future<Booking> f : fs) {
            assertEquals(expected, f.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertEquals(1, firsts.get());
        assertEquals(threads - 1, replays.get());
        assertSame(SeatState.BOOKED, svc.seatState("s", "A1"));
    }

    /** Proves the same key used for two DIFFERENT holds books only one. Thread 1 is paused after its key check; thread 2 must not slip through to book the other hold, so it blocks and then gets a conflict. */
    @Test
    void oneKeyCannotBookTwoHolds() throws Exception {
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        SeatHoldBookingService s = Support.service(clock, 4, Support.ids("h-"), Support.ids("b-"), (phase, seat) -> {
            if (phase == Gap.Phase.AFTER_KEY_LOOKUP && first.compareAndSet(true, false)) {
                paused.countDown();
                try {
                    resume.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        Hold h1 = s.hold("s", l("A1"));
        Hold h2 = s.hold("s", l("A2"));
        java.util.concurrent.atomic.AtomicReference<Object> r1 = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Object> r2 = new java.util.concurrent.atomic.AtomicReference<>();
        Thread t1 = new Thread(() -> r1.set(attempt(s, h1.holdId())));
        Thread t2 = new Thread(() -> r2.set(attempt(s, h2.holdId())));
        t1.start();
        assertTrue(paused.await(10, TimeUnit.SECONDS));
        t2.start();
        Support.awaitBlockedOrDone(t2);
        assertTrue(t2.isAlive(), "thread 2 must wait for the key, not finish while thread 1 is mid-confirm");
        resume.countDown();
        t1.join(10_000);
        t2.join(10_000);
        assertTrue(r1.get() instanceof Booking);
        assertTrue(r2.get() instanceof IdempotencyConflictException, String.valueOf(r2.get()));
        assertEquals(SeatState.BOOKED, s.seatState("s", "A1"));
        assertEquals(SeatState.HELD, s.seatState("s", "A2"));
    }

    /** Confirms with the shared key k and returns the booking or the exception, so a thread can hand it back. */
    private static Object attempt(SeatHoldBookingService s, String holdId) {
        try {
            return s.confirm(holdId, "k", "p").booking();
        } catch (BookingException e) {
            return e;
        }
    }

    /** Proves a replay returns the stored booking without drawing another booking id from the supplier. */
    @Test
    void replayDoesNotDrawANewBookingId() {
        AtomicInteger drawn = new AtomicInteger();
        SeatHoldBookingService s = Support.service(clock, 2, Support.ids("h-"), () -> "b-" + drawn.incrementAndGet(), Gap.NONE);
        Hold h = s.hold("s", l("A1"));
        s.confirm(h.holdId(), "k", "p");
        s.confirm(h.holdId(), "k", "p");
        s.confirm(h.holdId(), "k", "p");
        assertEquals(1, drawn.get());
    }

    /** Proves the request fingerprint cannot be fooled by a separator: hold "h1|x" with payment "y" and hold "h1" with payment "x|y" are different requests. */
    @Test
    void fingerprintHasNoSeparatorCollision() {
        java.util.Iterator<String> ids = l("h1|x", "h1").iterator();
        SeatHoldBookingService s = Support.service(clock, 3, ids::next, Support.ids("b-"), Gap.NONE);
        Hold a = s.hold("s", l("A1"));
        Hold b = s.hold("s", l("A2"));
        s.confirm(a.holdId(), "k", "y");
        assertThrows(IdempotencyConflictException.class, () -> s.confirm(b.holdId(), "k", "x|y"));
    }

    /** Proves that after the refund is marked done, a retry of the same expired confirm answers 410 again but does not create a second refund note. */
    @Test
    void retryAfterRefundDoneDoesNotRefundTwice() {
        Hold h = svc.hold("s", l("A1"));
        clock.advance(Duration.ofMinutes(5));
        assertThrows(HoldExpiredException.class, () -> svc.confirm(h.holdId(), "k", "pay-9"));
        svc.refundDone("k");
        assertThrows(HoldExpiredException.class, () -> svc.confirm(h.holdId(), "k", "pay-9"));
        assertTrue(svc.refundsNeeded().isEmpty());
    }

    /** Proves the refund tombstone matters when the key is forgotten before the hold: the key TTL (10 min) is shorter than the hold retention (1 h), so a late retry finds no stored outcome, reaches the expired hold again, and must still not create a second note after the first was paid. */
    @Test
    void paidRefundIsNotRecreatedAfterTheKeyExpired() {
        BookingConfig shortKeys = new BookingConfig(Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofHours(1));
        SeatHoldBookingService s = new SeatHoldBookingService(clock, shortKeys, Support.ids("h-"), Support.ids("b-"));
        s.addShow("s", Support.seats(2));
        Hold h = s.hold("s", l("A1"));
        clock.advance(Duration.ofMinutes(5));
        assertThrows(HoldExpiredException.class, () -> s.confirm(h.holdId(), "k", "pay-9"));
        s.refundDone("k");
        clock.advance(Duration.ofMinutes(10));
        assertThrows(HoldExpiredException.class, () -> s.confirm(h.holdId(), "k", "pay-9"));
        assertTrue(s.refundsNeeded().isEmpty());
    }

    /** Proves the same key with another payment reference after expiry is a conflict, so pay-10 is never silently ignored. */
    @Test
    void expiredConfirmWithDifferentPaymentIsAConflict() {
        Hold h = svc.hold("s", l("A1"));
        clock.advance(Duration.ofMinutes(5));
        assertThrows(HoldExpiredException.class, () -> svc.confirm(h.holdId(), "k", "pay-9"));
        assertThrows(IdempotencyConflictException.class, () -> svc.confirm(h.holdId(), "k", "pay-10"));
        assertEquals(1, svc.refundsNeeded().size());
    }

    /** Proves a clock that steps backwards cannot sell a seat twice: the old hold looks live again, but another hold owns the seat now, so the old confirm fails and the second hold books it. */
    @Test
    void backwardClockStepCannotSellASeatTwice() {
        Hold h1 = svc.hold("s", l("A1"));
        clock.advance(Duration.ofMinutes(5));
        Hold h2 = svc.hold("s", l("A1"));
        clock.advance(Duration.ofSeconds(-1));
        assertThrows(HoldExpiredException.class, () -> svc.confirm(h1.holdId(), "k1", "p1"));
        Booking b = svc.confirm(h2.holdId(), "k2", "p2").booking();
        assertEquals(h2.holdId(), b.holdId());
        assertEquals(SeatState.BOOKED, svc.seatState("s", "A1"));
    }

    /** Proves a confirm racing with a release ends with exactly one winner and a consistent seat state. */
    @Test
    void confirmVersusReleaseHasOneWinner() throws Exception {
        for (int round = 0; round < 50; round++) {
            SeatHoldBookingService s = Support.service(clock, 2);
            Hold h = s.hold("s", l("A1"));
            CountDownLatch gate = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<Boolean> c = pool.submit(() -> {
                gate.await();
                try {
                    s.confirm(h.holdId(), "k", "p");
                    return true;
                } catch (HoldNotFoundException e) {
                    return false;
                }
            });
            Future<Boolean> r = pool.submit(() -> {
                gate.await();
                try {
                    s.release(h.holdId());
                    return true;
                } catch (AlreadyConfirmedException e) {
                    return false;
                }
            });
            gate.countDown();
            boolean confirmed = c.get(10, TimeUnit.SECONDS);
            boolean released = r.get(10, TimeUnit.SECONDS);
            pool.shutdown();
            assertTrue(confirmed ^ released, "exactly one of confirm and release wins");
            assertEquals(confirmed ? SeatState.BOOKED : SeatState.AVAILABLE, s.seatState("s", "A1"));
        }
    }
}
