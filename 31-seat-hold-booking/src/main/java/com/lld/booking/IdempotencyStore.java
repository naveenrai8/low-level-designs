package com.lld.booking;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Remembers the outcome of each finished confirm for a limited time, so a retry with the same key gets the same answer.
 *
 * <p>An entry is dead AT its expiry instant (half-open, like holds), and {@link #lookup} checks that itself, so a key is never replayed past its time even if cleanup has not run. Cleanup is only a memory saver: it drops entries from the front while they are expired. Entries normally live equally long, so the front is the oldest, but a clock that steps backwards can put a younger-expiring entry behind an older one. That is why lookup never trusts the order.
 */
final class IdempotencyStore {

    /** What identifies a request: the hold and the payment reference. A record, so no separator can make two different requests look equal. */
    record Fingerprint(String holdId, String paymentRef) {
    }

    /** What a finished confirm ended with: a booking, or the hold had expired (the payment was noted for refund). */
    sealed interface Outcome permits Booked, Expired {
    }

    /** The confirm made this booking. */
    record Booked(Booking booking) implements Outcome {
    }

    /** The confirm found the hold expired. A retry of the same request gets the same 410 and no second refund note. */
    record Expired(String holdId) implements Outcome {
    }

    /** What is remembered per key. */
    private record Entry(Fingerprint fingerprint, Outcome outcome, Instant expiresAt) {
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /**
     * Looks the key up. Returns empty if unknown or expired, the stored outcome if the fingerprint matches, and throws if the key was used for a different request.
     */
    synchronized Optional<Outcome> lookup(String key, Fingerprint fingerprint, Instant now) {
        purge(now);
        Entry e = entries.get(key);
        if (e == null || !now.isBefore(e.expiresAt)) {
            return Optional.empty();
        }
        if (!e.fingerprint.equals(fingerprint)) {
            throw new IdempotencyConflictException(key);
        }
        return Optional.of(e.outcome);
    }

    /** Remembers the outcome for {@code key} until {@code expiresAt}. */
    synchronized void put(String key, Fingerprint fingerprint, Outcome outcome, Instant expiresAt) {
        entries.put(key, new Entry(fingerprint, outcome, expiresAt));
    }

    /** Frees memory: drops entries from the front while they are expired, and returns how many. It never changes a lookup answer. */
    synchronized int purge(Instant now) {
        int dropped = 0;
        Iterator<Entry> it = entries.values().iterator();
        while (it.hasNext() && !now.isBefore(it.next().expiresAt)) {
            it.remove();
            dropped++;
        }
        return dropped;
    }

    /** Returns how many keys are remembered right now (for tests of the memory bound). */
    synchronized int size() {
        return entries.size();
    }
}
