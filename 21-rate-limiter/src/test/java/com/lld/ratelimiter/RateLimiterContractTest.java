package com.lld.ratelimiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lld.ratelimiter.RateLimiterFactory.Algorithm;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Behaviour every algorithm must share. Written once and run against all three, which is the payoff of coding to the {@link RateLimiter} interface. */
class RateLimiterContractTest {

    private static final RateLimit FIVE_PER_SECOND = RateLimit.perSecond(5);

    private final FakeTicker ticker = new FakeTicker();

    /** Proves 8 requests at the same instant get exactly 5 permits with a limit of 5. */
    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void burstAtOneInstantGrantsExactlyTheLimit(Algorithm algorithm) {
        RateLimiter limiter = RateLimiterFactory.create(algorithm, FIVE_PER_SECOND, ticker);

        int allowed = 0;
        for (int i = 0; i < 8; i++) {
            if (limiter.tryAcquire("alice").allowed()) {
                allowed++;
            }
        }

        assertEquals(5, allowed);
    }

    /** Proves that after two quiet windows a used-up key gets its full limit back, and no more. */
    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void fullLimitIsAvailableAgainAfterTwoQuietWindows(Algorithm algorithm) {
        RateLimiter limiter = RateLimiterFactory.create(algorithm, FIVE_PER_SECOND, ticker);
        drain(limiter, "alice");

        ticker.advance(Duration.ofSeconds(2));

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("alice").allowed(), "permit " + i);
        }
        assertFalse(limiter.tryAcquire("alice").allowed());
    }

    /** Proves the wait a denial reports is long enough: after waiting it, the same request succeeds. */
    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void waitingRetryAfterIsEnoughForTheSameRequestToSucceed(Algorithm algorithm) {
        RateLimiter limiter = RateLimiterFactory.create(algorithm, FIVE_PER_SECOND, ticker);
        drain(limiter, "alice");

        Decision denied = limiter.tryAcquire("alice");
        assertFalse(denied.allowed());
        assertTrue(denied.retryAfter().compareTo(Duration.ZERO) > 0);

        ticker.advance(denied.retryAfter());

        assertTrue(limiter.tryAcquire("alice").allowed());
    }

    /** Proves one key running out does not affect another key. */
    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void keysAreLimitedIndependently(Algorithm algorithm) {
        RateLimiter limiter = RateLimiterFactory.create(algorithm, FIVE_PER_SECOND, ticker);
        drain(limiter, "alice");

        assertFalse(limiter.tryAcquire("alice").allowed());
        assertTrue(limiter.tryAcquire("bob").allowed());
    }

    /** Proves a request for several permits gets all of them or none, and a denied request takes nothing. */
    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void multiPermitRequestsAreAllOrNothing(Algorithm algorithm) {
        RateLimiter limiter = RateLimiterFactory.create(algorithm, FIVE_PER_SECOND, ticker);
        assertTrue(limiter.tryAcquire("alice", 3).allowed());

        Decision tooMany = limiter.tryAcquire("alice", 3); // only 2 left

        assertFalse(tooMany.allowed());
        assertEquals(2, tooMany.remaining());
        assertTrue(limiter.tryAcquire("alice", 2).allowed(), "the denied call consumed nothing");
    }

    /** Proves malformed requests (blank or null key, 0 permits, more permits than the limit) throw instead of being denied. */
    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void rejectsRequestsThatCanNeverSucceed(Algorithm algorithm) {
        RateLimiter limiter = RateLimiterFactory.create(algorithm, FIVE_PER_SECOND, ticker);

        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire("alice", 6));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire("alice", 0));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(" ", 1));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(null, 1));
    }

    /** Proves eviction forgets a key whose limit has fully recovered and keeps a key that was just used. */
    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void evictionOnlyDropsKeysWhoseLimitHasRecovered(Algorithm algorithm) {
        KeyedRateLimiter<?> limiter = RateLimiterFactory.create(algorithm, FIVE_PER_SECOND, ticker);
        drain(limiter, "old");
        ticker.advance(Duration.ofSeconds(3));
        limiter.tryAcquire("fresh");

        int evicted = limiter.evictIdle();

        assertEquals(1, evicted);
        assertEquals(1, limiter.trackedKeys(), "only 'fresh' remains");
    }

    /** Proves eviction changes nothing: the same traffic with eviction after every request gives exactly the same answers as never evicting. */
    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void evictionIsLossless(Algorithm algorithm) {
        FakeTicker tickerA = new FakeTicker();
        FakeTicker tickerB = new FakeTicker();
        KeyedRateLimiter<?> neverEvicts = RateLimiterFactory.create(algorithm, FIVE_PER_SECOND, tickerA);
        KeyedRateLimiter<?> alwaysEvicts = RateLimiterFactory.create(algorithm, FIVE_PER_SECOND, tickerB);
        Random random = new Random(42); // fixed seed: deterministic "random" traffic

        List<Boolean> a = new ArrayList<>();
        List<Boolean> b = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            long stepMillis = random.nextInt(10) == 0 ? 1_500 : random.nextInt(120);
            tickerA.advanceMillis(stepMillis);
            tickerB.advanceMillis(stepMillis);
            String key = "user-" + random.nextInt(3);

            a.add(neverEvicts.tryAcquire(key).allowed());
            b.add(alwaysEvicts.tryAcquire(key).allowed());
            alwaysEvicts.evictIdle();
        }

        assertEquals(a, b);
    }

    /** Uses up every permit {@code key} has right now, so a test can start from empty. */
    private static void drain(RateLimiter limiter, String key) {
        while (limiter.tryAcquire(key).allowed()) {
            // consume every available permit
        }
    }
}
