package com.lld.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Proves HALF_OPEN lets in exactly N probes under contention, and shows the check-then-act race that a naive gate loses. */
class HalfOpenConcurrencyTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    /** The broken version of {@link ProbeGate}: it checks "is a slot free" and then takes it in two separate steps, so two threads can both take the last slot. */
    static final class RacyProbeGate {
        private final int capacity;
        private final AtomicInteger used = new AtomicInteger();
        private final Runnable gap;

        /** Creates the broken gate with a hook between the check and the take. */
        RacyProbeGate(int capacity, Runnable gap) {
            this.capacity = capacity;
            this.gap = gap;
        }

        /** Checks, pauses at the hook, then takes: the classic check-then-act bug. */
        boolean tryAcquire() {
            int seen = used.get();
            if (seen >= capacity) {
                return false;
            }
            gap.run();
            used.set(seen + 1);
            return true;
        }
    }

    /** Waits at the barrier, turning checked exceptions into a test failure and restoring the interrupt flag. */
    private static Runnable gapAt(CyclicBarrier barrier) {
        return () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    /** Runs two threads that both try the last slot of a one-slot gate, and returns how many got it. */
    private static int twoThreadsRace(java.util.function.BooleanSupplier acquire) throws InterruptedException {
        AtomicInteger winners = new AtomicInteger();
        Runnable r = () -> {
            if (acquire.getAsBoolean()) {
                winners.incrementAndGet();
            }
        };
        Thread a = new Thread(r);
        Thread b = new Thread(r);
        a.setDaemon(true);
        b.setDaemon(true);
        a.start();
        b.start();
        a.join();
        b.join();
        return winners.get();
    }

    /** With a barrier forcing both threads to see "slot free" first, the broken gate gives the one slot to both: the bug exists. */
    @Test
    @Timeout(10)
    void racyGateLetsTwoThreadsTakeTheLastSlot() throws InterruptedException {
        RacyProbeGate gate = new RacyProbeGate(1, gapAt(new CyclicBarrier(2)));
        assertEquals(2, twoThreadsRace(gate::tryAcquire));
    }

    /** The same forced interleaving against the real gate: both read "free", but only one compare-and-set wins and the loser is rejected. */
    @Test
    @Timeout(10)
    void realGateGivesTheLastSlotToExactlyOne() throws InterruptedException {
        ProbeGate gate = new ProbeGate(1, gapAt(new CyclicBarrier(2)));
        assertEquals(1, twoThreadsRace(gate::tryAcquire));
        assertEquals(1, gate.used());
    }

    /** Releasing a slot that was never taken is a bug and must be loud, not clamped away. */
    @Test
    void releaseWithoutAcquireIsRejected() {
        ProbeGate gate = new ProbeGate(2);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, gate::release);
        assertEquals(0, gate.used());
        assertTrue(gate.tryAcquire());
        gate.release();
        assertEquals(0, gate.used());
    }

    /**
     * Many threads hit a half-open breaker at the same instant with 3 permitted probes: exactly 3 reach the dependency, the others are rejected, and 3 successes close it.
     *
     * <p>Honest note: the breaker's own lock already makes "take a probe" one-at-a-time, so this test would still pass if only the lock were removed from {@code acquire}; {@link ProbeGate}'s compare-and-set would still bound the count. The race itself is shown on the gate in isolation by the two tests above.
     */
    @Test
    @Timeout(20)
    void exactlyNProbesReachTheDependency() throws Exception {
        FakeTicker ticker = new FakeTicker();
        CircuitBreaker cb = new CircuitBreaker("x", CircuitBreakerConfig.countBased(50, 2, 2, WAIT, 3), ticker);
        Support.tryOnce(cb, false);
        Support.tryOnce(cb, false);
        ticker.advance(WAIT);
        assertEquals(CircuitState.HALF_OPEN, cb.state());
        int threads = 24;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch rejectedDone = new CountDownLatch(threads - 3);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        AtomicInteger reached = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    cb.call(() -> {
                        reached.incrementAndGet();
                        release.await();
                        return "ok";
                    });
                } catch (CircuitOpenException e) {
                    rejected.incrementAndGet();
                    rejectedDone.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    finished.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }
        start.countDown();
        try {
            assertTrue(rejectedDone.await(10, TimeUnit.SECONDS));
            assertEquals(3, reached.get());
        } finally {
            release.countDown();
        }
        assertTrue(finished.await(10, TimeUnit.SECONDS));
        assertEquals(threads - 3, rejected.get());
        assertEquals(CircuitState.CLOSED, cb.state());
    }
}
