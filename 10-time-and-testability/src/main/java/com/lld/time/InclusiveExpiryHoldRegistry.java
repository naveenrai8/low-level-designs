package com.lld.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * <b>Bug.</b> Treats a hold as active <em>through</em> {@code expiresAt}, the closed interval
 * {@code [createdAt, expiresAt]}. At exactly the expiry instant the old holder still blocks the
 * seat while the spec, the UI countdown and the payment page all say it is free. The off-by-one
 * is invisible in tests that use {@code Instant.now()} and {@code sleep}, because they never land
 * on the boundary.
 */
public final class InclusiveExpiryHoldRegistry extends ExpiringHoldRegistry {

    public InclusiveExpiryHoldRegistry(Clock clock, Scheduler scheduler, Duration ttl) {
        super(clock, scheduler, ttl);
    }

    @Override
    protected boolean isActive(Hold hold, Instant now) {
        return !now.isAfter(hold.expiresAt());       // BUG: inclusive end
    }
}
