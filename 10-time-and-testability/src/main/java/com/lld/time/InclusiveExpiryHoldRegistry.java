package com.lld.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * <b>Bug.</b> Treats a hold as active <em>through</em> {@code expiresAt}, the closed interval {@code [createdAt, expiresAt]}.
 * <p>Example: a hold expires at 10:00:00. At exactly 10:00:00 the old holder still blocks the seat, while the spec, the UI countdown and the payment page all say it is free, so the next user is turned away.
 * <p>The off-by-one is invisible in tests that use {@code Instant.now()} and {@code sleep}, because they never land on the boundary.
 */
public final class InclusiveExpiryHoldRegistry extends ExpiringHoldRegistry {

    /**
     * Creates the broken registry with the same inputs as the fixed one.
     */
    public InclusiveExpiryHoldRegistry(Clock clock, Scheduler scheduler, Duration ttl) {
        super(clock, scheduler, ttl);
    }

    /**
     * Uses {@code !now.isAfter(expiresAt)}, which is true at exactly the expiry instant. That is the bug.
     */
    @Override
    protected boolean isActive(Hold hold, Instant now) {
        return !now.isAfter(hold.expiresAt());       // BUG: inclusive end
    }
}
