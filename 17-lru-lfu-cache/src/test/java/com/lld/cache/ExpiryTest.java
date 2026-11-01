package com.lld.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** expireAfterWrite: half-open, lazy, overflow-safe, and not extended by reads. */
class ExpiryTest {

    private static final Duration TTL = Duration.ofSeconds(10);

    private final FakeTicker ticker = new FakeTicker();
    private final RecordingListener<String, Integer> listener = new RecordingListener<>();

    /** Builds a cache with the shared 10 second TTL, the given ticker and the recording listener. */
    private BoundedCache<String, Integer> cache(Policy policy, FakeTicker t) {
        return CacheBuilder.<String, Integer>newBuilder().policy(policy).maximumSize(2_000)
                .expireAfterWrite(TTL).ticker(t).removalListener(listener, (r, e) -> { }).buildBounded();
    }

    /** Proves an entry is still live one nanosecond before the TTL and expired exactly at it. */
    @ParameterizedTest
    @EnumSource(Policy.class)
    void liveOneNanosecondBeforeTheTtlAndGoneExactlyAtIt(Policy policy) {
        BoundedCache<String, Integer> cache = cache(policy, ticker);
        cache.put("a", 1);

        ticker.advanceNanos(TTL.toNanos() - 1);
        assertEquals(Optional.of(1), cache.get("a"));
        ticker.advanceNanos(1);
        assertEquals(Optional.empty(), cache.get("a"));

        assertEquals(List.of(new Removal<>("a", 1, RemovalCause.EXPIRED)), listener.events);
        assertEquals(new CacheStats(1, 1, 0, 1), cache.stats());
        assertEquals(0, cache.size());
        cache.checkInvariants();
    }

    /** Proves a read does not push the expiry back. */
    @ParameterizedTest
    @EnumSource(Policy.class)
    void readsDoNotExtendTheTtl(Policy policy) {
        BoundedCache<String, Integer> cache = cache(policy, ticker);
        cache.put("a", 1);
        ticker.advance(Duration.ofSeconds(6));
        assertTrue(cache.get("a").isPresent());

        ticker.advance(Duration.ofSeconds(4));

        assertEquals(Optional.empty(), cache.get("a"));
    }

    /** Proves writing the key again restarts its TTL. */
    @ParameterizedTest
    @EnumSource(Policy.class)
    void overwritingResetsTheTtl(Policy policy) {
        BoundedCache<String, Integer> cache = cache(policy, ticker);
        cache.put("a", 1);
        ticker.advance(Duration.ofSeconds(6));
        cache.put("a", 2);

        ticker.advance(Duration.ofSeconds(6));         // 12 s after the first write, 6 s after the second

        assertEquals(Optional.of(2), cache.get("a"));
    }

    /** Proves removing an already-expired key returns empty and reports EXPIRED, not EXPLICIT. */
    @ParameterizedTest
    @EnumSource(Policy.class)
    void removingAnExpiredKeyReturnsEmptyAndReportsExpired(Policy policy) {
        BoundedCache<String, Integer> cache = cache(policy, ticker);
        cache.put("a", 1);
        ticker.advance(TTL);

        assertEquals(Optional.empty(), cache.remove("a"));
        assertEquals(List.of(RemovalCause.EXPIRED), listener.events.stream().map(Removal::cause).toList());
    }

    /** Lazy expiry leaves dead entries counted in size(); cleanUp() is the scheduled sweep. */
    @ParameterizedTest
    @EnumSource(Policy.class)
    void cleanUpPurgesEverythingExpiredAndOnlyThat(Policy policy) {
        BoundedCache<String, Integer> cache = cache(policy, ticker);
        for (int i = 0; i < 1_000; i++) {
            cache.put("old-" + i, i);
        }
        ticker.advance(Duration.ofSeconds(5));
        cache.put("fresh", 0);
        ticker.advance(Duration.ofSeconds(5));          // old-*: exactly TTL; fresh: TTL / 2

        assertEquals(1_001, cache.size());              // nothing touched them yet
        assertEquals(1_000, cache.cleanUp());

        assertEquals(1, cache.size());
        assertEquals(1_000, cache.stats().expirations());
        cache.checkInvariants();
    }

    /**
     * nanoTime may be near Long.MAX_VALUE, so {@code now + ttl} wraps negative. The naive
     * {@code now >= expiresAt} would call the entry expired at once. {@code now - expiresAt >= 0} doesn't. The first {@code get} is the one that catches the naive check: there {@code now} is still positive and {@code expiresAt} has already wrapped negative.
     */
    @ParameterizedTest
    @EnumSource(Policy.class)
    void expiryIsCorrectAcrossNanoTimeOverflow(Policy policy) {
        FakeTicker nearOverflow = new FakeTicker(Long.MAX_VALUE - Duration.ofSeconds(5).toNanos());
        BoundedCache<String, Integer> cache = cache(policy, nearOverflow);
        cache.put("a", 1);
        assertEquals(Optional.of(1), cache.get("a"));   // now > 0, expiresAt < 0: naive `now >= expiresAt` says expired

        nearOverflow.advanceNanos(TTL.toNanos() - 1);  // the ticker itself has wrapped by now
        assertEquals(Optional.of(1), cache.get("a"));
        nearOverflow.advanceNanos(1);
        assertEquals(Optional.empty(), cache.get("a"));
    }

    /**
     * An expired entry that nothing touched yet can be the eviction victim. It left because its time was up, not because of size: report {@code EXPIRED} and count it as an expiration (as Caffeine does), so eviction stats aren't inflated by dead entries.
     */
    @ParameterizedTest
    @EnumSource(Policy.class)
    void anExpiredVictimIsReportedAsExpiredNotSize(Policy policy) {
        BoundedCache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder().policy(policy)
                .maximumSize(2).expireAfterWrite(TTL).ticker(ticker)
                .removalListener(listener, (r, e) -> { }).buildBounded();
        cache.put("a", 1);
        ticker.advance(TTL);                            // a is dead but untouched
        cache.put("b", 2);
        cache.put("c", 3);                              // full: the victim is a

        assertEquals(List.of(new Removal<>("a", 1, RemovalCause.EXPIRED)), listener.events);
        assertEquals(new CacheStats(0, 0, 0, 1), cache.stats());
        cache.checkInvariants();
    }

    /** Writing over a dead entry is an insert: EXPIRED for the old value, no REPLACED, LFU count 1. */
    @org.junit.jupiter.api.Test
    void aPutOverAnExpiredKeyIsAFreshInsertNotAReplace() {
        LfuPolicy<String> lfu = new LfuPolicy<>();
        BoundedCache<String, Integer> cache = new BoundedCache<>(lfu, 10, (k, v) -> 1, TTL.toNanos(),
                ticker, listener, (r, e) -> { });
        cache.put("a", 1);
        cache.get("a");
        cache.get("a");
        assertEquals(3, lfu.frequency("a"));
        ticker.advance(TTL);

        cache.put("a", 2);

        assertEquals(List.of(new Removal<>("a", 1, RemovalCause.EXPIRED)), listener.events);
        assertEquals(1, lfu.frequency("a"));
        assertEquals(Optional.of(2), cache.get("a"));
        cache.checkInvariants();
    }
}
