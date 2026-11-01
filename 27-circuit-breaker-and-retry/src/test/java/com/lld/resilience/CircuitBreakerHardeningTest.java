package com.lld.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Proves the corner cases that once let a probe slot leak or a half-open breaker hang: lost probes, throwing predicates, interrupts, and slot bookkeeping across periods. */
class CircuitBreakerHardeningTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Duration MAX_HALF_OPEN = Duration.ofSeconds(10);
    private final FakeTicker ticker = new FakeTicker();

    /** Builds a breaker that trips after 2 failures and allows {@code probes} half-open probes, with a 10 s limit on HALF_OPEN. */
    private CircuitBreaker breaker(int probes) {
        return new CircuitBreaker("x", CircuitBreakerConfig.countBased(50, 2, 2, WAIT, probes).withMaxWaitInHalfOpen(MAX_HALF_OPEN), ticker);
    }

    /** Trips the breaker with two failures and waits until it is HALF_OPEN. */
    private void tripAndHalfOpen(CircuitBreaker cb) {
        Support.tryOnce(cb, false);
        Support.tryOnce(cb, false);
        ticker.advance(WAIT);
        assertEquals(CircuitState.HALF_OPEN, cb.state());
    }

    /** If every probe is lost (never reports), HALF_OPEN rejects until exactly maxWait, then goes to OPEN, and the lost permits' late reports are dropped. */
    @Test
    void lostProbesDoNotWedgeHalfOpen() {
        CircuitBreaker cb = breaker(2);
        tripAndHalfOpen(cb);
        CallPermit lost1 = cb.acquire();
        CallPermit lost2 = cb.acquire();
        ticker.advanceNanos(MAX_HALF_OPEN.toNanos() - 1);
        assertEquals(CircuitState.HALF_OPEN, cb.state());
        assertThrows(CircuitOpenException.class, cb::acquire);
        ticker.advanceNanos(1);
        assertEquals(CircuitState.OPEN, cb.state());
        lost1.success();
        lost2.success();
        assertEquals(2, cb.metrics().staleResults());
        assertEquals(CircuitState.OPEN, cb.state());
        ticker.advance(WAIT);
        assertEquals(CircuitState.HALF_OPEN, cb.state());
        cb.acquire();
    }

    /** A predicate that throws must not leak the probe slot: the original error still reaches the caller, and the single slot is usable again. */
    @Test
    void throwingPredicateDoesNotLeakASlot() {
        CircuitBreaker cb = new CircuitBreaker("x", CircuitBreakerConfig.countBased(50, 2, 2, WAIT, 1), ticker, t -> {
            throw new IllegalStateException("predicate bug");
        });
        cb.acquire().failure();
        cb.acquire().failure();
        ticker.advance(WAIT);
        assertEquals(CircuitState.HALF_OPEN, cb.state());
        IOException e = assertThrows(IOException.class, () -> cb.call(() -> {
            throw new IOException("real");
        }));
        assertEquals("predicate bug", e.getSuppressed()[0].getMessage());
        cb.acquire();
    }

    /** A client cancellation (InterruptedException) is not the dependency's fault: it never trips the breaker. */
    @Test
    void interruptedCallsAreNotFailures() {
        CircuitBreaker cb = breaker(1);
        for (int i = 0; i < 5; i++) {
            assertThrows(InterruptedException.class, () -> cb.call(() -> {
                throw new InterruptedException("cancelled");
            }));
        }
        assertEquals(CircuitState.CLOSED, cb.state());
        assertEquals(0, cb.metrics().failures());
    }

    /** The probe slots are handed out again in every HALF_OPEN period (kills: forgetting to reset the gate on a state change). */
    @Test
    void probeSlotsResetInEachHalfOpenPeriod() {
        CircuitBreaker cb = breaker(2);
        tripAndHalfOpen(cb);
        CallPermit p1 = cb.acquire();
        cb.acquire();
        p1.failure();
        assertEquals(CircuitState.OPEN, cb.state());
        ticker.advance(WAIT);
        assertEquals(CircuitState.HALF_OPEN, cb.state());
        cb.acquire();
        cb.acquire();
        assertThrows(CircuitOpenException.class, cb::acquire);
    }

    /** Probe successes from an earlier HALF_OPEN period do not carry over (kills: forgetting to zero the success counter). */
    @Test
    void probeSuccessesDoNotCarryOverBetweenPeriods() {
        CircuitBreaker cb = breaker(2);
        tripAndHalfOpen(cb);
        CallPermit p1 = cb.acquire();
        CallPermit p2 = cb.acquire();
        p1.success();
        p2.failure();
        assertEquals(CircuitState.OPEN, cb.state());
        ticker.advance(WAIT);
        assertEquals(CircuitState.HALF_OPEN, cb.state());
        cb.acquire().success();
        assertEquals(CircuitState.HALF_OPEN, cb.state());
    }

    /** A probe that ends with no verdict gives its slot back (kills: forgetting to release on IGNORED). */
    @Test
    void ignoredProbeReturnsItsSlot() {
        CircuitBreaker cb = breaker(1);
        tripAndHalfOpen(cb);
        cb.acquire().ignore();
        cb.acquire();
    }

    /**
     * A stale "no verdict" from an old period must not free a slot of the current period (kills: checking the generation after the IGNORED branch).
     *
     * <p>With 2 slots: probe P1 fails and re-opens the breaker, then in the next period Q1 and Q2 take both slots. P2 from the old period now reports "no verdict". A third probe must still be refused.
     */
    @Test
    void staleIgnoreCannotFreeASlotInANewPeriod() {
        CircuitBreaker cb = breaker(2);
        tripAndHalfOpen(cb);
        CallPermit p1 = cb.acquire();
        CallPermit p2 = cb.acquire();
        p1.failure();
        ticker.advance(WAIT);
        assertEquals(CircuitState.HALF_OPEN, cb.state());
        cb.acquire();
        cb.acquire();
        p2.ignore();
        assertThrows(CircuitOpenException.class, cb::acquire);
        assertTrue(cb.metrics().staleResults() >= 1);
    }
}
