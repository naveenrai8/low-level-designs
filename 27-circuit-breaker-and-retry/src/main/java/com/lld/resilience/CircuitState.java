package com.lld.resilience;

import java.util.EnumSet;
import java.util.Set;

/**
 * The three states of a circuit breaker, plus the table of moves that are allowed between them.
 *
 * <p>The table is the whole state machine (see Topic 06). Anything not in the table, such as CLOSED straight to HALF_OPEN, is a bug and is rejected.
 */
public enum CircuitState {
    /** Normal: calls go through and their outcomes are recorded in the sliding window. */
    CLOSED,
    /** Tripped: calls are rejected at once without touching the dependency. */
    OPEN,
    /** Testing: a few probe calls are let through to see whether the dependency has recovered. */
    HALF_OPEN;

    /** Returns the states this state may move to. */
    public Set<CircuitState> allowedTargets() {
        return switch (this) {
            case CLOSED -> EnumSet.of(OPEN);
            case OPEN -> EnumSet.of(HALF_OPEN);
            case HALF_OPEN -> EnumSet.of(CLOSED, OPEN);
        };
    }

    /** Returns true if the table allows a move from this state to the target. */
    public boolean canGoTo(CircuitState target) {
        return allowedTargets().contains(target);
    }
}
