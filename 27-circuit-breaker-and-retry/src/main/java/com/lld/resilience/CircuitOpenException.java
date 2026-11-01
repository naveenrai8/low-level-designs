package com.lld.resilience;

/** Thrown when the breaker refuses a call without running it, because it is OPEN or its half-open probe slots are all taken. Retrying it at once is pointless, so {@link Retry} never retries it. */
public final class CircuitOpenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param name the breaker name, to show which dependency is down
     * @param state the state that caused the rejection
     */
    public CircuitOpenException(String name, CircuitState state) {
        super("circuit '" + name + "' rejected the call (" + state + ")");
    }
}
