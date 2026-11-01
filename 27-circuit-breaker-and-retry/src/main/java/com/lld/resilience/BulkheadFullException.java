package com.lld.resilience;

/** Thrown when a bulkhead has no free slot, so the call was never run. It says "we are busy", not "the dependency is broken", so the breaker does not count it. */
public final class BulkheadFullException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param name the bulkhead name
     * @param max the cap on concurrent calls that was hit
     */
    public BulkheadFullException(String name, int max) {
        super("bulkhead '" + name + "' is full (" + max + " concurrent calls)");
    }
}
