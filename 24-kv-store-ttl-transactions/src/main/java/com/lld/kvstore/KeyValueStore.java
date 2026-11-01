package com.lld.kvstore;

import java.time.Duration;
import java.util.Optional;

/**
 * A small key-value store where every key can optionally expire (TTL = "time to live").
 *
 * <p>Error contract: null keys or values throw {@link NullPointerException}; a zero or negative TTL throws {@link IllegalArgumentException}.
 *
 * <p>Expiry rule (half-open): a key set at time T with TTL d is alive for {@code T <= now < T + d} and gone at exactly {@code T + d}. An expired key is invisible to every method below, even before it is physically removed.
 */
public interface KeyValueStore {

    /** Stores the value with no expiry. If the key had a TTL before, that TTL is cleared (like Redis SET). */
    void set(String key, String value);

    /** Stores the value and makes it expire {@code ttl} from now. Any older TTL on the key is replaced. */
    void set(String key, String value, Duration ttl);

    /** Returns the value, or empty if the key is missing or expired. */
    Optional<String> get(String key);

    /** Tells whether the key exists and has not expired. */
    boolean exists(String key);

    /** Removes the key. Returns true only if a live key was removed; a missing or expired key returns false. */
    boolean delete(String key);

    /** Gives a live key a new TTL counted from now. Returns false if the key is missing or expired. */
    boolean expire(String key, Duration ttl);

    /** Returns the time left before the key expires, or empty if the key is missing, expired, or has no TTL. */
    Optional<Duration> ttl(String key);

    /** Counts live keys. Expired keys are not counted even if they are still physically stored. */
    int count();
}
