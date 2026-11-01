package com.lld.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Proves the retry budget, seeded jitter, which errors are retried, and how retry composes with the breaker. */
class RetryTest {

    private static final Duration BASE = Duration.ofMillis(100);
    private static final Duration CAP = Duration.ofMillis(1_000);
    private final Support.RecordingSleeper sleeper = new Support.RecordingSleeper();

    /** Builds a retry helper that retries IOException with an unlimited budget and seed 42. */
    private Retry retry(int attempts) {
        return new Retry(attempts, BASE, CAP, new Random(42), sleeper, RetryBudget.unlimited(), t -> t instanceof IOException);
    }

    /** Builds a retry helper that retries EVERY exception, so only the built-in refusals (breaker open, bulkhead full, interrupt) can stop the loop. */
    private Retry retryAnything(int attempts) {
        return new Retry(attempts, BASE, CAP, new Random(42), sleeper, RetryBudget.unlimited(), t -> true);
    }

    /** Returns the delays that equal jitter must produce for the seed, worked out independently of the class under test. */
    private static List<Long> expectedDelays(long seed, int count) {
        Random r = new Random(seed);
        List<Long> out = new ArrayList<>();
        long backoff = 100;
        for (int i = 0; i < count; i++) {
            long half = backoff / 2;
            out.add(half + r.nextLong(backoff - half + 1));
            backoff = Math.min(1_000, backoff * 2);
        }
        return out;
    }

    /** The delays are exactly repeatable for a seed, grow with the doubling backoff and stay inside half..full of each step. */
    @Test
    void seededJitterGivesAnExactDelaySequence() {
        Support.AlwaysFails dep = new Support.AlwaysFails();
        assertThrows(IOException.class, () -> retry(6).call(dep));
        assertEquals(6, dep.calls.get());
        List<Long> got = sleeper.delays.stream().map(Duration::toMillis).toList();
        assertEquals(expectedDelays(42, 5), got);
        long[] backoffs = {100, 200, 400, 800, 1_000};
        for (int i = 0; i < got.size(); i++) {
            assertTrue(got.get(i) >= backoffs[i] / 2 && got.get(i) <= backoffs[i]);
        }
    }

    /** Two real Retry instances with the same seed wait the identical times; a different seed gives a different sequence. */
    @Test
    void sameSeedSameDelaysDifferentSeedDiffers() {
        List<List<Duration>> runs = new ArrayList<>();
        for (long seed : new long[] {7, 7, 8}) {
            Support.RecordingSleeper s = new Support.RecordingSleeper();
            Retry r = new Retry(6, BASE, CAP, new Random(seed), s, RetryBudget.unlimited(), t -> true);
            assertThrows(IOException.class, () -> r.call(new Support.AlwaysFails()));
            runs.add(s.delays);
        }
        assertEquals(runs.get(0), runs.get(1));
        assertTrue(!runs.get(0).equals(runs.get(2)));
    }

    /** A call that fails twice and then works is retried twice and returns the value. */
    @Test
    void succeedsAfterRetries() throws Exception {
        AtomicInteger n = new AtomicInteger();
        String v = retry(5).call(() -> {
            if (n.incrementAndGet() < 3) {
                throw new IOException("flaky");
            }
            return "done";
        });
        assertEquals("done", v);
        assertEquals(3, n.get());
        assertEquals(2, sleeper.delays.size());
    }

    /** An exception that is not retryable is thrown at once: one call, no sleep. */
    @Test
    void nonRetryableIsNotRetried() {
        AtomicInteger n = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> retry(5).call(() -> {
            n.incrementAndGet();
            throw new IllegalArgumentException("bad input");
        }));
        assertEquals(1, n.get());
        assertEquals(0, sleeper.delays.size());
    }

    /** When attempts run out, the last failure is thrown. */
    @Test
    void lastFailureIsThrownWhenAttemptsRunOut() {
        AtomicInteger n = new AtomicInteger();
        IOException e = assertThrows(IOException.class, () -> retry(3).call(() -> {
            throw new IOException("fail " + n.incrementAndGet());
        }));
        assertEquals("fail 3", e.getMessage());
    }

    /** With a 10 percent budget, 100 always-failing requests make exactly 10 retries in total: 110 calls to the dependency, not 500. */
    @Test
    void budgetCapsRetriesAtTheExactFraction() {
        RetryBudget budget = new RetryBudget(100, 10);
        Retry r = new Retry(5, BASE, CAP, new Random(1), sleeper, budget, t -> t instanceof IOException);
        Support.AlwaysFails dep = new Support.AlwaysFails();
        for (int i = 0; i < 100; i++) {
            assertThrows(IOException.class, () -> r.call(dep));
        }
        assertEquals(110, dep.calls.get());
        assertEquals(10, sleeper.delays.size());
    }

    /** Quiet time cannot bank unlimited retries: after 1000 good requests the jar holds only its maximum. */
    @Test
    void jarIsCapped() {
        RetryBudget budget = new RetryBudget(100, 5);
        for (int i = 0; i < 1_000; i++) {
            budget.onRequest();
        }
        assertEquals(5, budget.available());
        int granted = 0;
        while (budget.tryRetry()) {
            granted++;
        }
        assertEquals(5, granted);
    }

    /** An empty jar refuses and takes nothing; bad budget settings are rejected. */
    @Test
    void emptyBudgetRefuses() {
        RetryBudget budget = new RetryBudget(100, 5);
        assertTrue(!budget.tryRetry());
        for (int i = 0; i < 9; i++) {
            budget.onRequest();
        }
        assertTrue(!budget.tryRetry());
        budget.onRequest();
        assertTrue(budget.tryRetry());
        assertThrows(IllegalArgumentException.class, () -> new RetryBudget(1001, 1));
    }

    /** An OPEN breaker's rejection is not retried: a single attempt and no sleeping, because asking again at once gets the same answer. */
    @Test
    void openBreakerRejectionIsNotRetried() {
        FakeTicker ticker = new FakeTicker();
        CircuitBreaker cb = new CircuitBreaker("x", CircuitBreakerConfig.countBased(50, 2, 2, Duration.ofSeconds(30), 1), ticker);
        Support.tryOnce(cb, false);
        Support.tryOnce(cb, false);
        AtomicInteger asked = new AtomicInteger();
        assertThrows(CircuitOpenException.class, () -> retryAnything(5).call(() -> cb.call(() -> asked.incrementAndGet())));
        assertEquals(0, asked.get());
        assertEquals(0, sleeper.delays.size());
        assertEquals(1, cb.metrics().rejected());
    }

    /** Retry outside, breaker inside: the dependency fails twice, the breaker trips, the third attempt is rejected and ends the loop, so a patient retry of 10 only touches the dependency twice. */
    @Test
    void breakerTrippingMidRetryStopsTheLoop() {
        FakeTicker ticker = new FakeTicker();
        CircuitBreaker cb = new CircuitBreaker("x", CircuitBreakerConfig.countBased(50, 2, 2, Duration.ofSeconds(30), 1), ticker);
        Support.AlwaysFails dep = new Support.AlwaysFails();
        assertThrows(CircuitOpenException.class, () -> retryAnything(10).call(() -> cb.call(dep)));
        assertEquals(2, dep.calls.get());
        assertEquals(2, sleeper.delays.size());
    }

    /** A full bulkhead is not retried even by a catch-all predicate: one attempt, no sleep. */
    @Test
    void bulkheadRejectionIsNotRetried() {
        AtomicInteger n = new AtomicInteger();
        assertThrows(BulkheadFullException.class, () -> retryAnything(5).call(() -> {
            n.incrementAndGet();
            throw new BulkheadFullException("b", 1);
        }));
        assertEquals(1, n.get());
        assertEquals(0, sleeper.delays.size());
    }

    /** An interrupt thrown by the call itself is not retried even by a catch-all predicate, and the interrupt flag is restored so the cancellation is not lost. */
    @Test
    void interruptFromTheCallIsNotRetriedAndFlagIsRestored() {
        AtomicInteger n = new AtomicInteger();
        assertThrows(InterruptedException.class, () -> retryAnything(5).call(() -> {
            n.incrementAndGet();
            throw new InterruptedException("cancelled");
        }));
        assertEquals(1, n.get());
        assertEquals(0, sleeper.delays.size());
        assertTrue(Thread.interrupted());
    }

    /** If the wait is interrupted, the interrupt flag is restored and the InterruptedException is thrown. */
    @Test
    void interruptDuringSleepRestoresTheFlag() {
        Sleeper interrupting = d -> {
            throw new InterruptedException("stop");
        };
        Retry r = new Retry(3, BASE, CAP, new Random(1), interrupting, RetryBudget.unlimited(), t -> true);
        assertThrows(InterruptedException.class, () -> r.call(new Support.AlwaysFails()));
        assertTrue(Thread.interrupted());
    }

    /** Bad retry settings are refused. */
    @Test
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> retry(0));
        assertThrows(IllegalArgumentException.class, () -> new Retry(2, CAP, BASE, new Random(), sleeper, RetryBudget.unlimited(), t -> true));
    }

    /** The exception thrown is the very same object the dependency threw, not a wrapper. */
    @Test
    void originalExceptionIsRethrown() {
        IOException original = new IOException("x");
        IOException got = assertThrows(IOException.class, () -> retry(2).call(() -> {
            throw original;
        }));
        assertSame(original, got);
    }
}
