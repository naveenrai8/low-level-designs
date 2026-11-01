package com.lld.time;

import java.time.Clock;
import java.time.Duration;

/**
 * <b>Bug.</b> The expiry timer removes "whatever is on this seat" instead of "the hold I was created for".
 * <p>Example: Alice holds seat 7 and releases it early. Bob takes seat 7. Alice's timer then fires on schedule and silently deletes Bob's hold, so Bob's checkout page still shows a countdown for a seat that is up for grabs again.
 */
public final class StaleTimerHoldRegistry extends ExpiringHoldRegistry {

    /**
     * Creates the broken registry with the same inputs as the fixed one.
     */
    public StaleTimerHoldRegistry(Clock clock, Scheduler scheduler, Duration ttl) {
        super(clock, scheduler, ttl);
    }

    /**
     * Builds a timer action that removes by seat only, so it can delete a newer hold. That is the bug.
     */
    @Override
    protected Runnable expiryTask(Hold hold) {
        return () -> holds.remove(hold.seat());      // BUG: removes by key only
    }
}
