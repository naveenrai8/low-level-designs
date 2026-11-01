package com.lld.idgen;

import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A single-JVM {@link NodeIdAssigner}: hands out the lowest free number and quarantines it after release.
 *
 * <p>Why quarantine: if a released number were handed out at once, a new generator on the same millisecond could repeat the old holder's IDs (same time, same node, same sequence). So a released number cannot be acquired again until the clock is past {@code releaseTime + quarantineMillis}, that is, at least {@code quarantineMillis + 1} ms later. Set the quarantine to the generators' step-back tolerance.
 *
 * <p>The {@code owner} must be a unique instance token (for example a random UUID made at start-up), never a hostname or a pod name: a restarted process with the same name would be handed its old number back while the old process may still be alive.
 *
 * <p>In production this table lives in ZooKeeper, etcd or a database row with a lease (a time limit that must be renewed), so a crashed node's number frees itself. See the README for that design delta.
 */
public final class InMemoryNodeRegistry implements NodeIdAssigner {

    private static final long NEVER = Long.MIN_VALUE / 2;

    private final Clock clock;
    private final long quarantineMillis;
    private final Map<String, Integer> byOwner = new HashMap<>();
    private final boolean[] taken = new boolean[SnowflakeLayout.MAX_NODE + 1];
    private final long[] releasedAt = new long[SnowflakeLayout.MAX_NODE + 1];

    /** Creates a registry. {@code quarantineMillis} is how long a released number stays unavailable, on top of the release millisecond itself. */
    public InMemoryNodeRegistry(Clock clock, long quarantineMillis) {
        if (quarantineMillis < 0) {
            throw new IllegalArgumentException("quarantineMillis must be >= 0");
        }
        this.clock = Objects.requireNonNull(clock);
        this.quarantineMillis = quarantineMillis;
        Arrays.fill(releasedAt, NEVER);
    }

    /** Returns the owner's number. An owner who asks twice gets the same number back, so retries are safe. Skips numbers that are taken or still in quarantine. */
    @Override
    public synchronized int acquire(String owner) {
        Integer existing = byOwner.get(owner);
        if (existing != null) {
            return existing;
        }
        long now = clock.millis();
        for (int node = 0; node < taken.length; node++) {
            if (!taken[node] && now > releasedAt[node] + quarantineMillis) {
                taken[node] = true;
                byOwner.put(owner, node);
                return node;
            }
        }
        throw new IllegalStateException("no node number is free (all " + taken.length + " taken or in quarantine)");
    }

    /** Frees the owner's number and starts its quarantine. The owner forgets the number, so asking again gets a different one. */
    @Override
    public synchronized void release(String owner) {
        Integer node = byOwner.remove(owner);
        if (node != null) {
            taken[node] = false;
            releasedAt[node] = clock.millis();
        }
    }
}
