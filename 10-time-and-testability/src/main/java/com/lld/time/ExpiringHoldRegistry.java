package com.lld.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>Fix.</b> Time is injected ({@link Clock} for "now", {@link Scheduler} for "later"), so every
 * boundary is testable to the nanosecond. Two rules make it correct:
 *
 * <ol>
 *   <li><b>The read path decides.</b> {@link #isActive} checks expiry on every read against the
 *       half-open interval. The scheduled sweep is only memory cleanup, and it can fire late, or
 *       not at all after a restart, without anyone seeing an expired hold as active.</li>
 *   <li><b>Timers remove only what they created.</b> {@link #expiryTask} uses
 *       {@code remove(key, value)}, so a timer for an old hold can't delete the next user's hold
 *       on the same seat.</li>
 * </ol>
 *
 * <p>The two deliberately broken subclasses each override one rule.
 */
public class ExpiringHoldRegistry implements HoldRegistry {

    private final Clock clock;
    private final Scheduler scheduler;
    private final Duration ttl;
    private final AtomicLong ids = new AtomicLong();
    protected final Map<String, Hold> holds = new ConcurrentHashMap<>();

    public ExpiringHoldRegistry(Clock clock, Scheduler scheduler, Duration ttl) {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive: " + ttl);
        }
        this.clock = clock;
        this.scheduler = scheduler;
        this.ttl = ttl;
    }

    @Override
    public Optional<Hold> tryHold(String seat, String user) {
        Hold[] created = new Hold[1];
        holds.compute(seat, (s, existing) -> {
            Instant now = clock.instant();              // read time inside the per-key lock
            if (existing != null && isActive(existing, now)) {
                return existing;
            }
            created[0] = new Hold(ids.incrementAndGet(), s, user, now, now.plus(ttl));
            return created[0];
        });
        if (created[0] == null) {
            return Optional.empty();
        }
        scheduler.schedule(ttl, expiryTask(created[0]));  // outside compute: no foreign work under the bin lock
        return Optional.of(created[0]);
    }

    @Override
    public Optional<Hold> activeHold(String seat) {
        Hold hold = holds.get(seat);
        return hold != null && isActive(hold, clock.instant()) ? Optional.of(hold) : Optional.empty();
    }

    @Override
    public boolean release(Hold hold) {
        return holds.remove(hold.seat(), hold);
    }

    @Override
    public int size() {
        return holds.size();
    }

    /** Half-open {@code [createdAt, expiresAt)}: at exactly {@code expiresAt} the hold is gone. */
    protected boolean isActive(Hold hold, Instant now) {
        return now.isBefore(hold.expiresAt());
    }

    /** Removes this exact hold, never whatever happens to occupy the seat when the timer fires. */
    protected Runnable expiryTask(Hold hold) {
        return () -> holds.remove(hold.seat(), hold);
    }
}
