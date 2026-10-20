package com.lld.time;

import java.time.Clock;
import java.time.Duration;

/**
 * <b>Bug.</b> The expiry timer removes "whatever is on this seat" instead of "the hold I was
 * created for". If user A releases early and user B takes the seat, A's timer fires on schedule
 * and silently deletes B's hold. B's checkout page still shows a countdown for a seat that is
 * up for grabs again.
 */
public final class StaleTimerHoldRegistry extends ExpiringHoldRegistry {

    public StaleTimerHoldRegistry(Clock clock, Scheduler scheduler, Duration ttl) {
        super(clock, scheduler, ttl);
    }

    @Override
    protected Runnable expiryTask(Hold hold) {
        return () -> holds.remove(hold.seat());      // BUG: removes by key only
    }
}
