package com.lld.elevator;

/**
 * A clock that counts simulation ticks. One tick is one step of the building (a car moves at most one floor per tick).
 *
 * <p>The elevator logic never reads the wall clock. Whoever drives the building owns the clock, so tests and the simulation stay repeatable. See Topic 10 for the idea.
 */
@FunctionalInterface
public interface Ticker {

    /** Returns the current tick. It only ever goes up. */
    long read();
}
