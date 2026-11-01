package com.lld.kvstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Tests TTL behaviour of the plain store: boundary, lazy expiry, bounded sweep, TTL clearing. */
class ExpiryTest {

    private final ManualClock clock = new ManualClock();
    private final InMemoryKvStore store = new InMemoryKvStore(clock);

    /** A key is alive one millisecond before its deadline and gone at exactly the deadline (half-open interval). */
    @Test
    void boundaryIsHalfOpen() {
        store.set("k", "v", Duration.ofSeconds(10));
        clock.advance(Duration.ofMillis(9_999));
        assertEquals(Optional.of("v"), store.get("k"));
        clock.advance(Duration.ofMillis(1));
        assertEquals(Optional.empty(), store.get("k"));
    }

    /** An expired key is invisible to get, exists, ttl and count even though no sweep has run. */
    @Test
    void expiredKeyInvisibleBeforeSweep() {
        store.set("a", "1", Duration.ofSeconds(1));
        store.set("b", "2");
        clock.advance(Duration.ofSeconds(1));
        assertFalse(store.exists("a"));
        assertEquals(Optional.empty(), store.ttl("a"));
        assertEquals(1, store.count());
    }

    /** Reading an expired key removes it lazily, so the memory is freed without any sweep. */
    @Test
    void lazyExpiryFreesMemoryOnRead() {
        store.set("a", "1", Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(2));
        assertEquals(1, store.physicalSize());
        store.get("a");
        assertEquals(0, store.physicalSize());
    }

    /** Setting a key without a TTL clears an older TTL, so the key then lives forever. */
    @Test
    void plainSetClearsOldTtl() {
        store.set("k", "v1", Duration.ofSeconds(5));
        store.set("k", "v2");
        clock.advance(Duration.ofDays(365));
        assertEquals(0, store.sweep(10));
        assertEquals(Optional.of("v2"), store.get("k"));
        assertEquals(Optional.empty(), store.ttl("k"));
    }

    /** A new TTL replaces the old one, and expire() restarts the countdown from now. */
    @Test
    void ttlReplacedAndExpireRestarts() {
        store.set("k", "v", Duration.ofSeconds(5));
        clock.advance(Duration.ofSeconds(4));
        assertTrue(store.expire("k", Duration.ofSeconds(5)));
        assertEquals(Optional.of(Duration.ofSeconds(5)), store.ttl("k"));
        clock.advance(Duration.ofSeconds(4));
        assertTrue(store.exists("k"));
        clock.advance(Duration.ofSeconds(1));
        assertFalse(store.exists("k"));
        assertFalse(store.expire("k", Duration.ofSeconds(5)));
    }

    /** The sweep removes only expired keys, and no more than maxRemovals per call. */
    @Test
    void sweepIsBoundedAndRemovesOnlyExpired() {
        for (int i = 0; i < 10; i++) {
            store.set("old" + i, "x", Duration.ofSeconds(1 + i));
        }
        store.set("live-ttl", "x", Duration.ofHours(1));
        store.set("live-forever", "x");
        clock.advance(Duration.ofSeconds(5));
        assertEquals(2, store.sweep(2));
        assertEquals(3, store.sweep(100));
        assertEquals(0, store.sweep(100));
        assertEquals(5 + 2, store.physicalSize());
        assertTrue(store.exists("live-ttl"));
        assertTrue(store.exists("live-forever"));
        assertTrue(store.exists("old9"));
        assertFalse(store.exists("old0"));
    }

    /** A key whose TTL was extended is not removed by the sweep at its old deadline (the index entry was replaced). */
    @Test
    void sweepIgnoresStaleIndexEntries() {
        store.set("k", "v", Duration.ofSeconds(1));
        store.set("k", "v", Duration.ofSeconds(100));
        clock.advance(Duration.ofSeconds(2));
        assertEquals(0, store.sweep(10));
        assertTrue(store.exists("k"));
    }

    /** delete reports true only for a live key; zero and negative TTLs are rejected. */
    @Test
    void deleteAndValidation() {
        store.set("k", "v", Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(1));
        assertFalse(store.delete("k"));
        assertThrows(IllegalArgumentException.class, () -> store.set("k", "v", Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> store.set("k", "v", Duration.ofSeconds(-1)));
        assertThrows(NullPointerException.class, () -> store.get(null));
    }
}
