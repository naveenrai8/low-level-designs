package com.lld.resilience;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Stops calling a dependency that keeps failing, so callers fail fast and the dependency gets room to recover.
 *
 * <p>CLOSED lets calls through and records each outcome in a sliding window. When the failure rate in the window reaches the threshold (and at least {@code minimumCalls} calls are in it) the breaker moves to OPEN. OPEN rejects every call at once without running it. After the wait has passed it moves to HALF_OPEN, which lets a few probe calls through: if all succeed it closes, if one fails it opens again.
 *
 * <p>Every state change bumps a <em>generation</em> number. A {@link CallPermit} remembers the generation it was issued in, and a result that arrives from an older generation is thrown away. Without this, a slow call that started in CLOSED could finish after the breaker re-opened and wrongly count towards closing it.
 *
 * <p>Concurrency: all state lives behind one lock ({@code synchronized}). The lock is held only for a few field updates and never while the dependency runs.
 */
public final class CircuitBreaker {

    private final String name;
    private final CircuitBreakerConfig config;
    private final Ticker ticker;
    private final Predicate<Throwable> countsAsFailure;
    private final ProbeGate probes;

    private CircuitState state = CircuitState.CLOSED;
    private long generation;
    private SlidingWindow window;
    private long openedAt;
    private long halfOpenedAt;
    private int halfOpenSuccesses;
    private long successes;
    private long failures;
    private long rejected;
    private long stale;
    private long transitions;

    /**
     * Creates a breaker that counts every exception as a failure.
     *
     * @param name a label for messages and metrics
     * @param config the thresholds
     * @param ticker the elapsed-time clock; tests pass a fake one
     */
    public CircuitBreaker(String name, CircuitBreakerConfig config, Ticker ticker) {
        this(name, config, ticker, t -> true);
    }

    /**
     * Creates a breaker that only counts some exceptions as failures.
     *
     * @param name a label for messages and metrics
     * @param config the thresholds
     * @param ticker the elapsed-time clock
     * @param countsAsFailure decides whether a thrown exception is the dependency's fault; a "not found" answer, for example, is not
     */
    public CircuitBreaker(String name, CircuitBreakerConfig config, Ticker ticker, Predicate<Throwable> countsAsFailure) {
        this.name = Objects.requireNonNull(name, "name");
        this.config = Objects.requireNonNull(config, "config");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.countsAsFailure = Objects.requireNonNull(countsAsFailure, "countsAsFailure");
        this.window = config.newWindow();
        this.probes = new ProbeGate(config.permittedHalfOpenCalls());
    }

    /** Returns the label given at creation. */
    public String name() {
        return name;
    }

    /**
     * Runs the call under the breaker's protection.
     *
     * <p>A thrown {@link Exception} or {@link Error} is reported as a failure (if the predicate says so) and then rethrown unchanged. A {@link CircuitOpenException}, a {@link BulkheadFullException} or an {@link InterruptedException} coming out of the call is not the dependency's fault (busy, or the client gave up) and counts for nothing. If the failure predicate itself throws, the call is reported as "no verdict" and the predicate's exception is added to the original one as suppressed, so the permit is never leaked.
     *
     * @param call the dependency call
     * @param <T> the result type
     * @return whatever the call returned
     * @throws CircuitOpenException if the breaker refused the call; the call was not run
     * @throws Exception whatever the call threw
     */
    public <T> T call(ThrowingSupplier<T> call) throws Exception {
        CallPermit permit = acquire();
        T result;
        try {
            result = call.get();
        } catch (CircuitOpenException | BulkheadFullException | InterruptedException e) {
            permit.ignore();
            throw e;
        } catch (Throwable t) {
            report(permit, t);
            throw t;
        }
        permit.success();
        return result;
    }

    /** Reports a thrown error to the permit: a failure if the predicate says so, otherwise (or if the predicate itself blows up) no verdict. */
    private void report(CallPermit permit, Throwable t) {
        boolean fails;
        try {
            fails = countsAsFailure.test(t);
        } catch (RuntimeException | Error predicateBug) {
            t.addSuppressed(predicateBug);
            fails = false;
        }
        if (fails) {
            permit.failure();
        } else {
            permit.ignore();
        }
    }

    /**
     * Asks for permission to make one call.
     *
     * <p>If the wait in OPEN has passed this is the moment the breaker moves to HALF_OPEN. In HALF_OPEN at most {@code permittedHalfOpenCalls} permits are in flight at once until the next state change (a probe that ends with no verdict gives its slot back).
     *
     * @return a permit that must be given exactly one outcome
     * @throws CircuitOpenException if the call is not allowed
     */
    public synchronized CallPermit acquire() {
        refresh();
        if (state == CircuitState.CLOSED || (state == CircuitState.HALF_OPEN && probes.tryAcquire())) {
            return new CallPermit(this, generation);
        }
        rejected++;
        throw new CircuitOpenException(name, state);
    }

    /** Returns the current state, first moving OPEN to HALF_OPEN if the wait has passed, so a monitor sees the truth. */
    public synchronized CircuitState state() {
        refresh();
        return state;
    }

    /** Returns a snapshot of the counters. */
    public synchronized BreakerMetrics metrics() {
        refresh();
        return new BreakerMetrics(state, successes, failures, rejected, stale, transitions);
    }

    /**
     * Applies the two clock-driven moves, each exactly at its limit and not one tick before.
     *
     * <p>OPEN goes to HALF_OPEN once the wait has passed. HALF_OPEN goes back to OPEN once it has lasted {@code maxWaitInHalfOpen} without finishing, which rescues a breaker whose probe was cancelled or hung and never reported. The new generation makes any late report from those lost probes stale.
     */
    private void refresh() {
        long now = ticker.read();
        if (state == CircuitState.OPEN && now - openedAt >= config.waitInOpenNanos()) {
            transition(CircuitState.HALF_OPEN);
        } else if (state == CircuitState.HALF_OPEN && now - halfOpenedAt >= config.maxWaitInHalfOpenNanos()) {
            transition(CircuitState.OPEN);
        }
    }

    /**
     * Applies one outcome from a permit, unless the permit is from an older generation.
     *
     * <p>A stale result is counted in {@code staleResults} and otherwise ignored, so it cannot touch the window, the probe count or the state.
     */
    synchronized void report(long permitGeneration, CallPermit.Outcome outcome) {
        if (permitGeneration != generation) {
            stale++;
            return;
        }
        if (outcome == CallPermit.Outcome.IGNORED) {
            if (state == CircuitState.HALF_OPEN) {
                probes.release();
            }
            return;
        }
        boolean ok = outcome == CallPermit.Outcome.SUCCESS;
        if (ok) {
            successes++;
        } else {
            failures++;
        }
        long now = ticker.read();
        if (state == CircuitState.CLOSED) {
            window.record(ok, now);
            int total = window.total(now);
            if (total >= config.minimumCalls() && (long) window.failures(now) * 100 >= (long) config.failureRatePercent() * total) {
                transition(CircuitState.OPEN);
            }
        } else if (state == CircuitState.HALF_OPEN) {
            if (!ok) {
                transition(CircuitState.OPEN);
            } else if (++halfOpenSuccesses >= config.permittedHalfOpenCalls()) {
                transition(CircuitState.CLOSED);
            }
        }
    }

    /**
     * Performs one state change if the table allows it.
     *
     * <p>Every change starts a new generation, empties the window, and sets up the new state (open time, fresh probe slots).
     *
     * @throws IllegalStateException if the table does not allow the move
     */
    synchronized void transition(CircuitState target) {
        if (!state.canGoTo(target)) {
            throw new IllegalStateException("illegal transition " + state + " -> " + target);
        }
        state = target;
        generation++;
        transitions++;
        window.clear();
        halfOpenSuccesses = 0;
        probes.reset();
        if (target == CircuitState.OPEN) {
            openedAt = ticker.read();
        } else if (target == CircuitState.HALF_OPEN) {
            halfOpenedAt = ticker.read();
        }
    }
}
