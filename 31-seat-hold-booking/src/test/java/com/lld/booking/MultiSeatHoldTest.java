package com.lld.booking;

import static com.lld.booking.Support.l;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** F1: holding several seats is all-or-nothing, and never deadlocks. */
class MultiSeatHoldTest {

    private final TestClock clock = new TestClock();

    /** Proves a failed multi-hold leaves no partial holds: A1 stays free when A2 was taken. */
    @Test
    void failedMultiHoldLeavesNothingBehind() {
        SeatHoldBookingService svc = Support.service(clock, 4);
        svc.hold("s", l("A2"));
        SeatUnavailableException e = assertThrows(SeatUnavailableException.class, () -> svc.hold("s", l("A1", "A2", "A3")));
        assertEquals(l("A2"), e.unavailableSeats());
        assertEquals(l("A1", "A3", "A4"), svc.availableSeats("s"));
    }

    /** Proves the error lists every blocking seat, not just the first. */
    @Test
    void errorListsAllBlockingSeats() {
        SeatHoldBookingService svc = Support.service(clock, 4);
        svc.hold("s", l("A1", "A4"));
        SeatUnavailableException e = assertThrows(SeatUnavailableException.class, () -> svc.hold("s", l("A4", "A3", "A1")));
        assertEquals(l("A1", "A4"), e.unavailableSeats());
    }

    /** Builds a service whose gap hook runs {@code hook} at the given phase. */
    private SeatHoldBookingService withGap(LockOrder order, Gap.Phase phase, Runnable hook) {
        SeatHoldBookingService svc = new SeatHoldBookingService(clock, Support.CONFIG, Support.ids("h-"), Support.ids("b-"), OverbookPolicy.NONE, order, (p, seat) -> {
            if (p == phase) {
                hook.run();
            }
        });
        svc.addShow("s", Support.seats(3));
        return svc;
    }

    /** Proves the bug: with locks taken in request order, two callers listing A1,A2 and A2,A1 meet after their first lock and deadlock. The test sees this as both calls never finishing, then interrupts them. */
    @Test
    void requestOrderLockingDeadlocks() throws Exception {
        CyclicBarrier bothHoldOne = new CyclicBarrier(2);
        SeatHoldBookingService svc = withGap(LockOrder.REQUEST_ORDER, Gap.Phase.AFTER_FIRST_LOCK, () -> await(bothHoldOne));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Hold> f1 = pool.submit(() -> svc.hold("s", l("A1", "A2")));
            Future<Hold> f2 = pool.submit(() -> svc.hold("s", l("A2", "A1")));
            assertThrows(TimeoutException.class, () -> f1.get(500, TimeUnit.MILLISECONDS));
            assertThrows(TimeoutException.class, () -> f2.get(500, TimeUnit.MILLISECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "interrupt must break the deadlock and release the locks");
        assertEquals(l("A1", "A2", "A3"), svc.availableSeats("s"));
    }

    /** Proves the fix: both callers start together (barrier before locking), and the gap hook records the first seat each thread locked. Both must have locked the smallest id, A1, whatever order they listed. Then exactly one wins and the other holds nothing. */
    @Test
    void sortedLockingTakesTheSmallestSeatFirstAndNeverPartiallySucceeds() throws Exception {
        CyclicBarrier bothReady = new CyclicBarrier(2);
        java.util.Map<Thread, String> firstLocked = new java.util.concurrent.ConcurrentHashMap<>();
        SeatHoldBookingService svc = new SeatHoldBookingService(clock, Support.CONFIG, Support.ids("h-"), Support.ids("b-"), OverbookPolicy.NONE, LockOrder.SORTED, (p, seat) -> {
            if (p == Gap.Phase.BEFORE_LOCKING) {
                await(bothReady);
            } else if (p == Gap.Phase.AFTER_FIRST_LOCK) {
                firstLocked.put(Thread.currentThread(), seat);
            }
        });
        svc.addShow("s", Support.seats(3));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Hold> f1 = pool.submit(() -> svc.hold("s", l("A1", "A2")));
            Future<Hold> f2 = pool.submit(() -> svc.hold("s", l("A2", "A1")));
            int ok = 0;
            int failed = 0;
            for (Future<Hold> f : List.of(f1, f2)) {
                try {
                    assertEquals(l("A1", "A2"), f.get(5, TimeUnit.SECONDS).seatIds());
                    ok++;
                } catch (ExecutionException e) {
                    SeatUnavailableException su = assertInstanceOf(SeatUnavailableException.class, e.getCause());
                    assertEquals(l("A1", "A2"), su.unavailableSeats());
                    failed++;
                }
            }
            assertEquals(1, ok);
            assertEquals(1, failed);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, firstLocked.size());
        assertEquals(List.of("A1", "A1"), List.copyOf(firstLocked.values()));
        assertEquals(l("A3"), svc.availableSeats("s"));
    }

    /** Proves that if the gap hook throws after the first lock is taken, that lock is released again and the service still works. */
    @Test
    void locksAreReleasedWhenTheHookThrows() {
        AtomicBoolean armed = new AtomicBoolean(true);
        SeatHoldBookingService svc = new SeatHoldBookingService(clock, Support.CONFIG, Support.ids("h-"), Support.ids("b-"), OverbookPolicy.NONE, LockOrder.SORTED, (p, seat) -> {
            if (p == Gap.Phase.AFTER_FIRST_LOCK && armed.compareAndSet(true, false)) {
                throw new IllegalStateException("boom");
            }
        });
        svc.addShow("s", Support.seats(3));
        assertThrows(IllegalStateException.class, () -> svc.hold("s", l("A1", "A2")));
        assertTrue(!svc.seatLocked("s", "A1") && !svc.seatLocked("s", "A2"));
        svc.hold("s", l("A1", "A2"));
    }

    /** Proves under load, behind a start gate, that no seat is ever held by two holds at once and every attempt is counted exactly once. */
    @Test
    void hammerNeverDoubleHoldsAndTotalsAreExact() throws Exception {
        int threads = 16;
        int perThread = 400;
        int seatCount = 8;
        SeatHoldBookingService svc = Support.service(clock, seatCount);
        AtomicBoolean[] owned = new AtomicBoolean[seatCount + 1];
        for (int i = 1; i <= seatCount; i++) {
            owned[i] = new AtomicBoolean();
        }
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        AtomicInteger violations = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> done = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            long seed = t;
            done.add(pool.submit(() -> {
                Random r = new Random(seed);
                gate.await();
                for (int i = 0; i < perThread; i++) {
                    List<String> want = new ArrayList<>(new java.util.LinkedHashSet<>(List.of("A" + (1 + r.nextInt(seatCount)), "A" + (1 + r.nextInt(seatCount)), "A" + (1 + r.nextInt(seatCount)))));
                    java.util.Collections.shuffle(want, r);
                    try {
                        Hold h = svc.hold("s", want);
                        for (String s : h.seatIds()) {
                            if (!owned[Integer.parseInt(s.substring(1))].compareAndSet(false, true)) {
                                violations.incrementAndGet();
                            }
                        }
                        success.incrementAndGet();
                        for (String s : h.seatIds()) {
                            owned[Integer.parseInt(s.substring(1))].set(false);
                        }
                        svc.release(h.holdId());
                    } catch (SeatUnavailableException e) {
                        failure.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        gate.countDown();
        for (Future<?> f : done) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(0, violations.get());
        assertEquals(threads * perThread, success.get() + failure.get());
        assertTrue(success.get() > 0 && failure.get() > 0, "the test must exercise both outcomes");
        assertEquals(seatCount, svc.availableSeats("s").size());
    }

    /** Waits at the barrier, turning checked exceptions into runtime ones (also used to break out on interrupt). */
    private static void await(CyclicBarrier b) {
        try {
            b.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (BrokenBarrierException e) {
            throw new IllegalStateException(e);
        }
    }
}
