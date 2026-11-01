package com.lld.connpool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lld.connpool.Fakes.FakeConn;
import com.lld.connpool.Fakes.Factory;
import com.lld.connpool.Fakes.ManualTicker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Behaviour tests for {@link BoundedPool}. Nothing sleeps: time is a manual ticker and every thread interleaving is forced with latches and probes. */
class ConnectionPoolTest {

    private static final Duration LONG = Duration.ofHours(1);
    private static final long SEC = 1_000_000_000L;

    private final Factory factory = new Factory();
    private final ManualTicker ticker = new ManualTicker();
    private final ExecutorService exec = Executors.newCachedThreadPool();

    /** Stops any helper threads left by a test so a failure does not leak them into the next test. */
    @AfterEach
    void tearDown() {
        exec.shutdownNow();
    }

    /** Finds the fake behind a lent handle, by id (call it while the handle is still active). */
    private FakeConn raw(Connection lent) {
        long id = lent.id();
        return factory.made.stream().filter(c -> c.id() == id).findFirst().orElseThrow();
    }

    /** Builds a pool with default idle and leak limits (10 minutes and 30 seconds). */
    private BoundedPool pool(int min, int max, Probe probe) {
        return new BoundedPool(factory, PoolConfig.of(min, max), ticker, probe);
    }

    /** Returns a probe that releases a semaphore permit each time a borrower is about to start waiting. */
    private static Probe waitSignal(Semaphore entered) {
        return new Probe() {
            /** Tells the test a borrower reached the point just before waiting. */
            @Override
            public void beforeWait() {
                entered.release();
            }
        };
    }

    /** Waits for a latch/semaphore signal, failing the test (instead of hanging) if it never comes. */
    private static void await(Semaphore s) throws InterruptedException {
        assertTrue(s.tryAcquire(5, TimeUnit.SECONDS), "expected signal never came");
    }

    /** Proves the basics: a returned connection is reused, so only one is ever opened. */
    @Test
    void borrowReturnReusesTheSameConnection() throws Exception {
        BoundedPool p = pool(0, 2, Probe.NONE);
        Connection a = p.borrow(LONG);
        long aId = a.id();
        p.release(a);
        Connection b = p.borrow(LONG);
        assertNotSame(a, b);
        assertEquals(aId, b.id());
        assertEquals(1, factory.creates.get());
    }

    /** Proves the cap holds under contention: 24 threads fight for 3 slots behind a start gate and the live peak never passes 3. */
    @Test
    @Timeout(30)
    void peakNeverExceedsMaxUnderContention() throws Exception {
        BoundedPool p = pool(0, 3, Probe.NONE);
        int threads = 24;
        int rounds = 200;
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            fs.add(exec.submit(() -> {
                gate.await();
                for (int i = 0; i < rounds; i++) {
                    Connection c = p.borrow(Duration.ofHours(1));
                    p.release(c);
                }
                return null;
            }));
        }
        gate.countDown();
        for (Future<?> f : fs) {
            f.get();
        }
        assertTrue(factory.peak.get() <= 3, "peak was " + factory.peak.get());
        assertTrue(factory.live.get() <= 3);
        Stats s = p.stats();
        assertEquals(0, s.borrowed());
        assertEquals(s.idle(), s.total());
    }

    /** Forces the classic check-then-create race: while borrower A is paused after reserving the last slot, B must find no room and wait, so exactly one connection is created. */
    @Test
    @Timeout(20)
    void checkThenCreateRaceCreatesExactlyOne() throws Exception {
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch openGate = new CountDownLatch(1);
        Semaphore waiting = new Semaphore(0);
        Probe probe = new Probe() {
            /** Pauses the creator right after it reserved its slot. */
            @Override
            public void afterReserveBeforeCreate() {
                reserved.countDown();
                try {
                    openGate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            /** Tells the test the second borrower chose to wait. */
            @Override
            public void beforeWait() {
                waiting.release();
            }
        };
        BoundedPool p = pool(0, 1, probe);
        Future<Connection> a = exec.submit(() -> p.borrow(LONG));
        assertTrue(reserved.await(5, TimeUnit.SECONDS));
        Future<Connection> b = exec.submit(() -> p.borrow(LONG));
        // If B also thought there was room it would sit in the creator hook and never reach this signal.
        await(waiting);
        assertEquals(0, factory.creates.get());
        openGate.countDown();
        Connection ca = a.get();
        long caId = ca.id();
        p.release(ca);
        assertEquals(caId, b.get().id());
        assertEquals(1, factory.creates.get());
        assertEquals(1, factory.peak.get());
    }

    /** Proves a bounded wait: with the only connection out, a borrow gives up once the fake clock passes its timeout, and leaves the pool unchanged. */
    @Test
    @Timeout(20)
    void borrowGivesUpAfterTimeout() throws Exception {
        Semaphore entered = new Semaphore(0);
        BoundedPool p = pool(0, 1, waitSignal(entered));
        Connection held = p.borrow(LONG);
        Future<Connection> f = exec.submit(() -> p.borrow(Duration.ofSeconds(5)));
        await(entered);
        ticker.advance(4 * SEC);
        p.onTimeAdvanced();
        // Not yet expired: the waiter must go back to waiting, not give up early.
        await(entered);
        assertFalse(f.isDone());
        ticker.advance(SEC);
        p.onTimeAdvanced();
        ExecutionException e = assertThrows(ExecutionException.class, f::get);
        assertTrue(e.getCause() instanceof PoolTimeoutException);
        Stats s = p.stats();
        assertEquals(0, s.waiting());
        assertEquals(1, s.total());
        p.release(held);
    }

    /** Proves a zero timeout never waits: it fails at once on the calling thread when nothing is free. */
    @Test
    void zeroTimeoutFailsImmediately() throws Exception {
        BoundedPool p = pool(0, 1, Probe.NONE);
        p.borrow(LONG);
        assertThrows(PoolTimeoutException.class, () -> p.borrow(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> p.borrow(Duration.ofSeconds(-1)));
    }

    /** Proves one return wakes exactly one of two waiters, and tells signal from signalAll: the first woken waiter is frozen inside the lock, and with signalAll the other one would already be queued on that lock. */
    @Test
    @Timeout(20)
    void returnWakesExactlyOneWaiter() throws Exception {
        Semaphore entered = new Semaphore(0);
        AtomicInteger wakes = new AtomicInteger();
        CountDownLatch freeze = new CountDownLatch(1);
        Probe probe = new Probe() {
            /** Tells the test a borrower is about to wait. */
            @Override
            public void beforeWait() {
                entered.release();
            }

            /** Counts wake-ups and freezes the first one (still holding the pool lock) until the test lets it go. */
            @Override
            public void afterWake() {
                if (wakes.incrementAndGet() == 1) {
                    try {
                        freeze.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
        BoundedPool p = pool(0, 1, probe);
        Connection held = p.borrow(LONG);
        BlockingQueue<Connection> got = new ArrayBlockingQueue<>(2);
        for (int i = 0; i < 2; i++) {
            exec.submit(() -> {
                got.put(p.borrow(LONG));
                return null;
            });
        }
        await(entered);
        await(entered);
        assertEquals(2, p.stats().waiting());
        p.release(held);
        spinUntil(() -> wakes.get() >= 1);
        // signalAll would have moved the second waiter onto the lock queue already; signal leaves it parked on the condition.
        assertFalse(p.hasThreadsQueuedOnLock());
        freeze.countDown();
        Connection first = got.poll(5, TimeUnit.SECONDS);
        assertNotNull(first);
        assertNull(got.peek());
        assertEquals(1, wakes.get());
        p.release(first);
        assertNotNull(got.poll(5, TimeUnit.SECONDS));
    }

    /** Spins (no sleeping) until the condition holds, failing the test if it never does. */
    private static void spinUntil(java.util.function.BooleanSupplier cond) {
        long end = System.nanoTime() + 5_000_000_000L;
        while (!cond.getAsBoolean()) {
            assertTrue(System.nanoTime() < end, "condition never became true");
            Thread.onSpinWait();
        }
    }

    /** Proves no lost wake-up by forcing the window: the waiter, having found nothing free, is held until a releaser is seen queued on the pool lock. The release can only run once the waiter is parked, and it must still wake it. */
    @Test
    @Timeout(20)
    void returnBetweenCheckAndWaitIsNotLost() throws Exception {
        AtomicReference<BoundedPool> ref = new AtomicReference<>();
        CountDownLatch inGap = new CountDownLatch(1);
        Probe probe = new Probe() {
            /** Holds the waiter in the gap until the releaser is queued on the lock (bounded spin), or gives up after 2 seconds. */
            @Override
            public void beforeWait() {
                inGap.countDown();
                long end = System.nanoTime() + 2_000_000_000L;
                while (!ref.get().hasThreadsQueuedOnLock() && System.nanoTime() < end) {
                    Thread.onSpinWait();
                }
            }
        };
        BoundedPool p = pool(0, 1, probe);
        ref.set(p);
        Connection held = p.borrow(LONG);
        long heldId = held.id();
        Future<Connection> waiter = exec.submit(() -> p.borrow(Duration.ofHours(1)));
        assertTrue(inGap.await(5, TimeUnit.SECONDS));
        Future<?> releaser = exec.submit(() -> p.release(held));
        assertEquals(heldId, waiter.get().id());
        releaser.get();
    }

    /** Proves an interrupted waiter restores nothing it never took: no slot leaks, no waiter count leaks, and the pool still works. */
    @Test
    @Timeout(20)
    void interruptedWaiterLeaksNothing() throws Exception {
        Semaphore entered = new Semaphore(0);
        BoundedPool p = pool(0, 1, waitSignal(entered));
        Connection held = p.borrow(LONG);
        long heldId = held.id();
        AtomicBoolean flagRestored = new AtomicBoolean();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                p.borrow(Duration.ofHours(1));
            } catch (InterruptedException e) {
                thrown.set(e);
                Thread.currentThread().interrupt();
                flagRestored.set(Thread.currentThread().isInterrupted());
            }
        });
        t.start();
        await(entered);
        t.interrupt();
        t.join(5_000);
        assertTrue(thrown.get() instanceof InterruptedException);
        assertTrue(flagRestored.get());
        Stats s = p.stats();
        assertEquals(0, s.waiting());
        assertEquals(1, s.total());
        p.release(held);
        assertEquals(heldId, p.borrow(LONG).id());
    }

    /** Proves a connection held past the threshold is reported with the borrower's stack and thread, and a returned one is not. */
    @Test
    void leakIsReportedWithBorrowStack() throws Exception {
        BoundedPool p = pool(0, 2, Probe.NONE);
        Connection c = p.borrow(LONG);
        ticker.advance(29 * SEC);
        assertTrue(p.findLeaks().isEmpty());
        ticker.advance(SEC);
        List<Leak> leaks = p.findLeaks();
        assertEquals(1, leaks.size());
        Leak leak = leaks.get(0);
        assertEquals(c.id(), leak.connectionId());
        assertEquals(30 * SEC, leak.heldNanos());
        assertEquals(Thread.currentThread().getName(), leak.threadName());
        boolean fromThisTest = false;
        for (StackTraceElement e : leak.borrowStack().getStackTrace()) {
            fromThisTest |= e.getMethodName().equals("leakIsReportedWithBorrowStack");
        }
        assertTrue(fromThisTest, "stack should point at the borrowing method");
        p.release(c);
        assertTrue(p.findLeaks().isEmpty());
    }

    /** Proves stack capture can be switched off: the leak is still reported, with no stack. */
    @Test
    void leakWithoutStackCapture() throws Exception {
        PoolConfig cfg = new PoolConfig(0, 1, Duration.ofMinutes(1), Duration.ofSeconds(1), false);
        BoundedPool p = new BoundedPool(factory, cfg, ticker);
        p.borrow(LONG);
        ticker.advance(2 * SEC);
        assertNull(p.findLeaks().get(0).borrowStack());
    }

    /** Proves bad returns are rejected: a double return, a return of a stranger and a return after invalidate all throw and change nothing. */
    @Test
    void doubleAndForeignReturnsAreRejected() throws Exception {
        BoundedPool p = pool(0, 2, Probe.NONE);
        Connection c = p.borrow(LONG);
        p.release(c);
        assertThrows(IllegalStateException.class, () -> p.release(c));
        Connection stranger = new Factory().create();
        assertThrows(IllegalStateException.class, () -> p.release(stranger));
        Connection d = p.borrow(LONG);
        p.invalidate(d);
        assertThrows(IllegalStateException.class, () -> p.release(d));
        assertThrows(IllegalStateException.class, () -> p.invalidate(d));
        Stats s = p.stats();
        assertEquals(0, s.total());
        assertEquals(0, s.idle());
    }

    /** Proves a bad idle connection is closed and replaced, never handed out. */
    @Test
    void badIdleConnectionIsReplaced() throws Exception {
        BoundedPool p = pool(0, 1, Probe.NONE);
        Connection lent = p.borrow(LONG);
        FakeConn bad = raw(lent);
        p.release(lent);
        bad.valid = false;
        Connection next = p.borrow(LONG);
        assertNotEquals(bad.id(), next.id());
        assertTrue(bad.closed);
        assertEquals(2, factory.creates.get());
        assertEquals(1, factory.live.get());
        assertEquals(1, p.stats().total());
    }

    /** Proves a validation check that throws is treated as "bad", not as a crash of the borrow. */
    @Test
    void validationThatThrowsCountsAsBad() throws Exception {
        BoundedPool p = pool(0, 1, Probe.NONE);
        Connection lent = p.borrow(LONG);
        FakeConn c = raw(lent);
        p.release(lent);
        c.throwOnValidate = true;
        assertNotEquals(c.id(), p.borrow(LONG).id());
        assertTrue(c.closed);
    }

    /** Proves that when every idle connection is bad the borrower still gets a fresh one, because each dropped connection gave its slot back. */
    @Test
    void allIdleBadStillServes() throws Exception {
        BoundedPool p = pool(0, 3, Probe.NONE);
        List<Connection> held = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            held.add(p.borrow(LONG));
        }
        for (Connection c : held) {
            raw(c).valid = false;
            p.release(c);
        }
        Connection fresh = p.borrow(LONG);
        assertFalse(factory.made.subList(0, 3).stream().anyMatch(c -> c.id() == fresh.id()));
        assertEquals(3, factory.closes.get());
        assertTrue(factory.peak.get() <= 3);
    }

    /** Proves a failed create gives its slot back: after two failures a pool of 2 still reaches 2, and a third borrow times out. */
    @Test
    void failedCreateDoesNotLeakASlot() throws Exception {
        BoundedPool p = pool(0, 2, Probe.NONE);
        factory.failNext.set(2);
        assertThrows(ConnectionException.class, () -> p.borrow(LONG));
        assertThrows(ConnectionException.class, () -> p.borrow(LONG));
        assertEquals(0, p.stats().total());
        p.borrow(LONG);
        p.borrow(LONG);
        assertEquals(2, p.stats().total());
        assertThrows(PoolTimeoutException.class, () -> p.borrow(Duration.ZERO));
    }

    /** Proves a failed create wakes a waiter: A fails to open, so B (waiting for the slot) gets to open one. */
    @Test
    @Timeout(20)
    void failedCreateWakesAWaiter() throws Exception {
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch openGate = new CountDownLatch(1);
        Semaphore waiting = new Semaphore(0);
        AtomicBoolean first = new AtomicBoolean(true);
        Probe probe = new Probe() {
            /** Pauses only the first creator so the second borrower can queue behind it. */
            @Override
            public void afterReserveBeforeCreate() {
                if (first.getAndSet(false)) {
                    reserved.countDown();
                    try {
                        openGate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            /** Tells the test the second borrower is waiting. */
            @Override
            public void beforeWait() {
                waiting.release();
            }
        };
        BoundedPool p = pool(0, 1, probe);
        factory.failNext.set(1);
        Future<Connection> a = exec.submit(() -> p.borrow(LONG));
        assertTrue(reserved.await(5, TimeUnit.SECONDS));
        Future<Connection> b = exec.submit(() -> p.borrow(LONG));
        await(waiting);
        openGate.countDown();
        ExecutionException e = assertThrows(ExecutionException.class, a::get);
        assertTrue(e.getCause() instanceof ConnectionException);
        assertNotNull(b.get());
        assertEquals(1, p.stats().total());
    }

    /** Proves idle eviction closes only old idle connections, never goes under min, and never touches a borrowed one. */
    @Test
    void evictionRespectsMinAndBorrowed() throws Exception {
        BoundedPool p = pool(1, 5, Probe.NONE);
        List<Connection> cs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            cs.add(p.borrow(LONG));
        }
        FakeConn held = raw(cs.get(0));
        for (int i = 1; i < 4; i++) {
            p.release(cs.get(i));
        }
        assertEquals(0, p.evictIdle());
        ticker.advance(11 * 60 * SEC);
        // Total 4, min 1: three idle are old, so all three may go; the borrowed one stays.
        assertEquals(3, p.evictIdle());
        assertFalse(held.closed);
        assertEquals(1, p.stats().total());
        p.release(cs.get(0));
        ticker.advance(11 * 60 * SEC);
        assertEquals(0, p.evictIdle());
        assertEquals(1, p.stats().total());
        assertEquals(1, factory.live.get());
    }

    /** Proves eviction stops at min even when more idle connections are old enough. */
    @Test
    void evictionStopsAtMin() throws Exception {
        BoundedPool p = pool(2, 5, Probe.NONE);
        List<Connection> cs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            cs.add(p.borrow(LONG));
        }
        cs.forEach(p::release);
        ticker.advance(11 * 60 * SEC);
        assertEquals(2, p.evictIdle());
        assertEquals(2, p.stats().total());
        assertEquals(2, factory.live.get());
    }

    /** Proves the newest idle connection is reused first, so the oldest ones age out and get evicted while a busy one stays warm. */
    @Test
    void recentlyUsedConnectionSurvivesEviction() throws Exception {
        BoundedPool p = pool(0, 2, Probe.NONE);
        Connection a = p.borrow(LONG);
        Connection b = p.borrow(LONG);
        p.release(a);
        p.release(b);
        ticker.advance(11 * 60 * SEC);
        Connection again = p.borrow(LONG);
        long againId = again.id();
        p.release(again);
        assertEquals(1, p.evictIdle());
        assertEquals(1, p.stats().idle());
        assertEquals(againId, p.borrow(LONG).id());
    }

    /** Proves warmUp opens up to min, and a failure during warm-up frees its slot so a retry reaches the target. */
    @Test
    void warmUpReachesMinEvenAfterAFailure() {
        BoundedPool p = pool(2, 2, Probe.NONE);
        factory.failNext.set(1);
        assertThrows(ConnectionException.class, p::warmUp);
        assertEquals(0, p.stats().total());
        p.warmUp();
        assertEquals(2, p.stats().idle());
        assertEquals(2, factory.live.get());
    }

    /** Proves close() closes idle connections, rejects borrows, and closes a borrowed connection when it comes back. */
    @Test
    void closeShutsEverything() throws Exception {
        BoundedPool p = pool(0, 2, Probe.NONE);
        Connection out = p.borrow(LONG);
        p.release(p.borrow(LONG));
        p.close();
        assertEquals(1, factory.live.get());
        assertThrows(IllegalStateException.class, () -> p.borrow(LONG));
        p.release(out);
        assertEquals(0, factory.live.get());
        assertEquals(0, p.stats().total());
    }

    /** Proves a waiter is released with an error when the pool closes, instead of waiting for ever. */
    @Test
    @Timeout(20)
    void closeWakesWaiters() throws Exception {
        Semaphore entered = new Semaphore(0);
        BoundedPool p = pool(0, 1, waitSignal(entered));
        p.borrow(LONG);
        Future<Connection> f = exec.submit(() -> p.borrow(Duration.ofHours(1)));
        await(entered);
        p.close();
        ExecutionException e = assertThrows(ExecutionException.class, f::get);
        assertTrue(e.getCause() instanceof IllegalStateException);
    }

    /** Differential check: random single-thread borrow/release/invalidate steps against a plain list model; counters and the factory's live count must always agree and never pass max. */
    @Test
    void randomOperationsMatchModel() throws Exception {
        Random rnd = new Random(42);
        int max = 4;
        BoundedPool p = pool(0, max, Probe.NONE);
        List<Connection> out = new ArrayList<>();
        for (int step = 0; step < 5_000; step++) {
            int op = rnd.nextInt(5);
            if (op <= 1 && out.size() < max) {
                out.add(p.borrow(Duration.ZERO));
            } else if (op == 2 && !out.isEmpty()) {
                Connection c = out.remove(rnd.nextInt(out.size()));
                if (rnd.nextBoolean()) {
                    p.release(c);
                } else {
                    p.invalidate(c);
                }
            } else if (op == 3 && !out.isEmpty()) {
                raw(out.get(rnd.nextInt(out.size()))).valid = rnd.nextBoolean();
            } else if (op == 4) {
                ticker.advance(rnd.nextInt(5) * 200 * SEC);
                p.evictIdle();
            }
            Stats s = p.stats();
            assertEquals(out.size(), s.borrowed());
            assertEquals(s.idle() + s.borrowed(), s.total());
            assertEquals(s.total(), factory.live.get());
            assertTrue(s.total() <= max);
        }
        assertTrue(factory.peak.get() <= max);
    }

    /** Proves a stale handle cannot be returned twice after the connection was lent again: A returns, B borrows, A's second return throws and B still owns it alone. */
    @Test
    void staleDoubleReturnAfterRelendIsRejected() throws Exception {
        BoundedPool p = pool(0, 1, Probe.NONE);
        Connection a = p.borrow(LONG);
        p.release(a);
        Connection b = p.borrow(LONG);
        assertThrows(IllegalStateException.class, () -> p.release(a));
        assertThrows(IllegalStateException.class, a::id);
        assertThrows(IllegalStateException.class, a::isValid);
        assertEquals(1, p.stats().borrowed());
        assertEquals(0, p.stats().idle());
        // A third borrower finds nothing free: B was not handed to someone else by the stale return.
        assertThrows(PoolTimeoutException.class, () -> p.borrow(Duration.ZERO));
        assertTrue(b.id() > 0);
    }

    /** Proves K slow bad idle connections cannot add up past the deadline: each check burns 2 s of fake time, so after three the 5 s budget is gone and the borrow times out instead of opening a fourth. */
    @Test
    void badIdleConnectionsCannotPushBorrowPastDeadline() throws Exception {
        BoundedPool p = pool(0, 3, Probe.NONE);
        List<Connection> cs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            cs.add(p.borrow(LONG));
        }
        cs.forEach(p::release);
        factory.made.forEach(c -> c.valid = false);
        factory.onValidate = () -> ticker.advance(2 * SEC);
        assertThrows(PoolTimeoutException.class, () -> p.borrow(Duration.ofSeconds(5)));
        assertEquals(3, factory.creates.get());
        assertEquals(0, p.stats().total());
        assertEquals(0, factory.live.get());
    }

    /** Runs {@code action} on one thread while a fake close is held half-way, starts a second borrower on a max=1 pool, and proves it parks: the slot is only freed after the close finishes, so the live peak stays 1. */
    private void assertSlotHeldUntilCloseFinishes(BoundedPool p, Semaphore parked, Callable<?> action) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        factory.closeGate = gate;
        Future<?> x = exec.submit(action);
        await(factory.closeEntered);
        BlockingQueue<Connection> got = new ArrayBlockingQueue<>(1);
        exec.submit(() -> {
            got.put(p.borrow(LONG));
            return null;
        });
        await(parked);
        assertEquals(1, factory.live.get());
        assertEquals(1, factory.peak.get());
        gate.countDown();
        x.get();
        Connection c = got.poll(5, TimeUnit.SECONDS);
        assertNotNull(c);
        p.release(c);
        assertEquals(1, factory.peak.get());
    }

    /** Proves a bad idle connection keeps its slot until its close finishes (validate path). */
    @Test
    @Timeout(20)
    void validateDropFreesSlotOnlyAfterClose() throws Exception {
        Semaphore parked = new Semaphore(0);
        BoundedPool p = pool(0, 1, waitSignal(parked));
        p.release(p.borrow(LONG));
        factory.made.get(0).valid = false;
        assertSlotHeldUntilCloseFinishes(p, parked, () -> {
            p.release(p.borrow(LONG));
            return null;
        });
    }

    /** Proves an invalidated connection keeps its slot until its close finishes. */
    @Test
    @Timeout(20)
    void invalidateFreesSlotOnlyAfterClose() throws Exception {
        Semaphore parked = new Semaphore(0);
        BoundedPool p = pool(0, 1, waitSignal(parked));
        Connection c = p.borrow(LONG);
        assertSlotHeldUntilCloseFinishes(p, parked, () -> {
            p.invalidate(c);
            return null;
        });
    }

    /** Proves an evicted connection keeps its slot until its close finishes. */
    @Test
    @Timeout(20)
    void evictionFreesSlotOnlyAfterClose() throws Exception {
        Semaphore parked = new Semaphore(0);
        BoundedPool p = pool(0, 1, waitSignal(parked));
        p.release(p.borrow(LONG));
        ticker.advance(11 * 60 * SEC);
        assertSlotHeldUntilCloseFinishes(p, parked, p::evictIdle);
    }

    /** Proves close() racing an in-flight open leaves nothing behind: the late connection is closed, the borrower is refused, live and total end at 0. */
    @Test
    @Timeout(20)
    void closeWhileOpeningRefusesAndLeaksNothing() throws Exception {
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        Probe probe = new Probe() {
            /** Pauses the creator after it reserved its slot so close() can run in the middle. */
            @Override
            public void afterReserveBeforeCreate() {
                reserved.countDown();
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        BoundedPool p = pool(0, 1, probe);
        Future<Connection> a = exec.submit(() -> p.borrow(LONG));
        assertTrue(reserved.await(5, TimeUnit.SECONDS));
        p.close();
        gate.countDown();
        ExecutionException e = assertThrows(ExecutionException.class, a::get);
        assertTrue(e.getCause() instanceof IllegalStateException);
        assertEquals(0, factory.live.get());
        assertEquals(0, p.stats().total());
    }

    /** Proves warmUp opens exactly {@code min} connections when min is below max. */
    @Test
    void warmUpOpensExactlyMin() {
        BoundedPool p = pool(2, 5, Probe.NONE);
        p.warmUp();
        assertEquals(2, factory.creates.get());
        assertEquals(2, p.stats().idle());
    }

    /** Proves the eviction boundary: idle for one nanosecond less than maxIdle stays, idle for exactly maxIdle goes. */
    @Test
    void evictionBoundaryIsInclusive() throws Exception {
        BoundedPool p = pool(0, 2, Probe.NONE);
        p.release(p.borrow(LONG));
        ticker.advance(10 * 60 * SEC - 1);
        assertEquals(0, p.evictIdle());
        ticker.advance(1);
        assertEquals(1, p.evictIdle());
    }

    /** Proves a leak threshold of zero switches leak detection off (HikariCP's meaning of 0) instead of flagging every loan. */
    @Test
    void zeroLeakThresholdMeansOff() throws Exception {
        PoolConfig cfg = new PoolConfig(0, 1, Duration.ofMinutes(1), Duration.ZERO, true);
        BoundedPool p = new BoundedPool(factory, cfg, ticker);
        p.borrow(LONG);
        ticker.advance(100 * SEC);
        assertTrue(p.findLeaks().isEmpty());
    }
}
