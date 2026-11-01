package com.lld.ledger;

/**
 * Test seam: called at named points inside the ledger's critical section so a test can force a particular interleaving.
 * <p>Points are "locked:ACCOUNT" (just after taking that account's lock) and "checked" (after the funds check, before the write). Production passes {@link #NONE}.
 */
@FunctionalInterface
public interface Gap {

    Gap NONE = point -> { };

    /**
     * Called at a named point. Does nothing in production; a test may wait on a barrier or record the order.
     *
     * @param point the name of the point
     */
    void at(String point);
}
