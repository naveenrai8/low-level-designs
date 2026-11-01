package com.lld.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Proves the state machine: thresholds, minimum calls, fast rejection, the exact wait boundary, half-open and the window boundary. */
class CircuitBreakerTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private final FakeTicker ticker = new FakeTicker();

    /** Builds a count-window breaker: trips at 50 percent after at least 4 of the last 4 calls, 2 half-open probes. */
    private CircuitBreaker countBreaker() {
        return new CircuitBreaker("db", CircuitBreakerConfig.countBased(50, 4, 4, WAIT, 2), ticker);
    }

    /** Feeds the breaker a list of outcomes (true = success). */
    private void feed(CircuitBreaker cb, boolean... outcomes) {
        for (boolean o : outcomes) {
            Support.tryOnce(cb, o);
        }
    }

    /** Trips a fresh count breaker to OPEN with four failures. */
    private CircuitBreaker openBreaker() {
        CircuitBreaker cb = countBreaker();
        feed(cb, false, false, false, false);
        assertEquals(CircuitState.OPEN, cb.state());
        return cb;
    }

    /** A bad rate is ignored until the window holds the minimum number of calls. */
    @Test
    void ratesAreNotJudgedBeforeMinimumCalls() {
        CircuitBreaker cb = countBreaker();
        feed(cb, false, false, false);
        assertEquals(CircuitState.CLOSED, cb.state());
        feed(cb, false);
        assertEquals(CircuitState.OPEN, cb.state());
    }

    /** A rate just under the threshold keeps the breaker closed; at the threshold it trips. */
    @Test
    void thresholdIsInclusive() {
        CircuitBreaker below = new CircuitBreaker("a", CircuitBreakerConfig.countBased(50, 4, 4, WAIT, 1), ticker);
        feed(below, true, true, true, false);
        assertEquals(CircuitState.CLOSED, below.state());
        CircuitBreaker at = new CircuitBreaker("b", CircuitBreakerConfig.countBased(50, 4, 4, WAIT, 1), ticker);
        feed(at, true, true, false, false);
        assertEquals(CircuitState.OPEN, at.state());
    }

    /** OPEN rejects calls without running the dependency. */
    @Test
    void openRejectsWithoutCallingTheDependency() {
        CircuitBreaker cb = openBreaker();
        AtomicInteger ran = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            assertThrows(CircuitOpenException.class, () -> cb.call(() -> ran.incrementAndGet()));
        }
        assertEquals(0, ran.get());
        assertEquals(5, cb.metrics().rejected());
    }

    /** The wait is exact: one nanosecond before it the breaker is still OPEN, at it the breaker is HALF_OPEN. */
    @Test
    void waitBoundaryIsExact() {
        CircuitBreaker cb = openBreaker();
        ticker.advanceNanos(WAIT.toNanos() - 1);
        assertEquals(CircuitState.OPEN, cb.state());
        assertThrows(CircuitOpenException.class, () -> cb.call(() -> "x"));
        ticker.advanceNanos(1);
        assertEquals(CircuitState.HALF_OPEN, cb.state());
    }

    /** Enough probe successes close the breaker and a fresh window starts empty. */
    @Test
    void halfOpenSuccessesClose() {
        CircuitBreaker cb = openBreaker();
        ticker.advance(WAIT);
        feed(cb, true);
        assertEquals(CircuitState.HALF_OPEN, cb.state());
        feed(cb, true);
        assertEquals(CircuitState.CLOSED, cb.state());
        feed(cb, false, false, false);
        assertEquals(CircuitState.CLOSED, cb.state());
    }

    /** One probe failure re-opens at once, and the wait starts again from that moment. */
    @Test
    void halfOpenFailureReopensAndRestartsTheWait() {
        CircuitBreaker cb = openBreaker();
        ticker.advance(WAIT);
        feed(cb, false);
        assertEquals(CircuitState.OPEN, cb.state());
        ticker.advanceNanos(WAIT.toNanos() - 1);
        assertEquals(CircuitState.OPEN, cb.state());
        ticker.advanceNanos(1);
        assertEquals(CircuitState.HALF_OPEN, cb.state());
    }

    /** Old outcomes leave a count window: three old failures no longer count once pushed out. */
    @Test
    void countWindowForgetsOldOutcomes() {
        CircuitBreaker cb = countBreaker();
        feed(cb, false, true, true, true, true, true, true);
        assertEquals(CircuitState.CLOSED, cb.state());
        feed(cb, false);
        assertEquals(CircuitState.CLOSED, cb.state());
        feed(cb, false);
        assertEquals(CircuitState.OPEN, cb.state());
    }

    /** In a time window an outcome is gone at exactly window-length age: one tick earlier it still counts and trips, at the boundary it has left and does not. */
    @Test
    void timeWindowBoundaryIsExact() {
        Duration window = Duration.ofSeconds(10);
        CircuitBreakerConfig cfg = CircuitBreakerConfig.timeBased(50, 2, window, WAIT, 1);
        CircuitBreaker early = new CircuitBreaker("e", cfg, ticker);
        feed(early, false);
        ticker.advanceNanos(window.toNanos() - 1);
        feed(early, true);
        assertEquals(CircuitState.OPEN, early.state());
        ticker.advance(WAIT);
        CircuitBreaker onEdge = new CircuitBreaker("o", cfg, ticker);
        feed(onEdge, false);
        ticker.advanceNanos(window.toNanos());
        feed(onEdge, true);
        assertEquals(CircuitState.CLOSED, onEdge.state());
    }

    /** The table accepts exactly CLOSED to OPEN, OPEN to HALF_OPEN, HALF_OPEN to CLOSED and HALF_OPEN to OPEN. */
    @Test
    void transitionTableAllowsOnlyTheFourMoves() {
        int allowed = 0;
        for (CircuitState from : CircuitState.values()) {
            for (CircuitState to : CircuitState.values()) {
                if (from.canGoTo(to)) {
                    allowed++;
                }
            }
        }
        assertEquals(4, allowed);
        assertFalse(CircuitState.CLOSED.canGoTo(CircuitState.HALF_OPEN));
        assertFalse(CircuitState.OPEN.canGoTo(CircuitState.CLOSED));
    }

    /** Forcing an illegal move throws and leaves the state alone. */
    @Test
    void illegalTransitionIsRejected() {
        CircuitBreaker cb = countBreaker();
        assertThrows(IllegalStateException.class, () -> cb.transition(CircuitState.HALF_OPEN));
        assertThrows(IllegalStateException.class, () -> cb.transition(CircuitState.CLOSED));
        assertEquals(CircuitState.CLOSED, cb.state());
    }

    /** A late success from a call admitted in CLOSED must not count as a probe success after the breaker has re-opened and gone half-open. */
    @Test
    void staleSuccessCannotCloseAReopenedBreaker() {
        CircuitBreaker cb = new CircuitBreaker("x", CircuitBreakerConfig.countBased(50, 2, 2, WAIT, 1), ticker);
        CallPermit slow = cb.acquire();
        feed(cb, false, false);
        ticker.advance(WAIT);
        assertEquals(CircuitState.HALF_OPEN, cb.state());
        slow.success();
        assertEquals(CircuitState.HALF_OPEN, cb.state());
        assertEquals(1, cb.metrics().staleResults());
    }

    /** A late failure from an old generation must not re-open a breaker that is healthy again. */
    @Test
    void staleFailureCannotReopenAClosedBreaker() {
        CircuitBreaker cb = new CircuitBreaker("x", CircuitBreakerConfig.countBased(50, 2, 2, WAIT, 1), ticker);
        CallPermit slow = cb.acquire();
        feed(cb, false, false);
        ticker.advance(WAIT);
        feed(cb, true);
        assertEquals(CircuitState.CLOSED, cb.state());
        slow.failure();
        assertEquals(CircuitState.CLOSED, cb.state());
        assertEquals(1, cb.metrics().staleResults());
    }

    /** A permit accepts one outcome only. */
    @Test
    void permitReportsOnce() {
        CircuitBreaker cb = countBreaker();
        CallPermit p = cb.acquire();
        p.success();
        assertThrows(IllegalStateException.class, p::failure);
    }

    /** An exception the predicate calls "not the dependency's fault" does not count towards tripping. */
    @Test
    void ignoredExceptionsDoNotCount() {
        CircuitBreaker cb = new CircuitBreaker("x", CircuitBreakerConfig.countBased(50, 2, 2, WAIT, 1), ticker, t -> !(t instanceof IllegalArgumentException));
        for (int i = 0; i < 5; i++) {
            assertThrows(IllegalArgumentException.class, () -> cb.call(() -> {
                throw new IllegalArgumentException("not found");
            }));
        }
        assertEquals(CircuitState.CLOSED, cb.state());
        assertEquals(0, cb.metrics().failures());
    }

    /** A bulkhead rejection passing through the breaker is "we are busy", not a failure, so it never trips the breaker. */
    @Test
    void bulkheadRejectionIsNotABreakerFailure() {
        CircuitBreaker cb = new CircuitBreaker("x", CircuitBreakerConfig.countBased(50, 2, 2, WAIT, 1), ticker);
        for (int i = 0; i < 5; i++) {
            assertThrows(BulkheadFullException.class, () -> cb.call(() -> {
                throw new BulkheadFullException("b", 1);
            }));
        }
        assertEquals(CircuitState.CLOSED, cb.state());
    }

    /** An Error thrown by the call is counted as a failure and rethrown. */
    @Test
    void errorsCountAsFailuresAndPropagate() {
        CircuitBreaker cb = new CircuitBreaker("x", CircuitBreakerConfig.countBased(50, 2, 2, WAIT, 1), ticker);
        for (int i = 0; i < 2; i++) {
            assertThrows(StackOverflowError.class, () -> cb.call(() -> {
                throw new StackOverflowError();
            }));
        }
        assertEquals(CircuitState.OPEN, cb.state());
    }

    /** Bad settings are refused when the config is built. */
    @Test
    void configValidation() {
        assertThrows(IllegalArgumentException.class, () -> CircuitBreakerConfig.countBased(0, 1, 4, WAIT, 1));
        assertThrows(IllegalArgumentException.class, () -> CircuitBreakerConfig.countBased(50, 5, 4, WAIT, 1));
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreakerConfig(50, 1, 4, 100, 5, 1, 5));
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreakerConfig(50, 1, 0, 0, 5, 1, 5));
        assertThrows(IllegalArgumentException.class, () -> CircuitBreakerConfig.countBased(50, 1, 4, WAIT, 0));
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreakerConfig(50, 1, 4, 0, 5, 1, 0));
    }
}
