package com.lld.resilience;

/**
 * A snapshot of one breaker's counters.
 *
 * @param state the current state
 * <p>Results that arrive from an older generation are counted only in {@code staleResults}; they never touch {@code successes} or {@code failures}.
 *
 * @param successes calls that succeeded and were counted
 * @param failures calls that failed and were counted
 * @param rejected calls refused without running (OPEN, or no half-open slot)
 * @param staleResults results dropped because the breaker changed state while the call was in flight
 * @param transitions how many state changes have happened
 */
public record BreakerMetrics(CircuitState state, long successes, long failures, long rejected, long staleResults, long transitions) {
}
