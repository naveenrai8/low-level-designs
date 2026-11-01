package com.lld.scheduler;

/**
 * A clock that only moves forward, counted in nanoseconds.
 *
 * <p>The scheduler never asks the real clock for the time. It asks a {@code Ticker}, and a test moves a fake ticker by hand. That is what makes every test here instant and repeatable (see Topic 10).
 *
 * <p>There is deliberately no "real" implementation in this module: production code passes {@code System::nanoTime}.
 */
@FunctionalInterface
public interface Ticker {

    /** Returns the current reading in nanoseconds. The starting point is arbitrary, so only differences between two readings mean anything. */
    long read();
}
