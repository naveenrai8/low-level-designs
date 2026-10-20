package com.lld.ratelimiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lld.ratelimiter.RateLimiters.Algorithm;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Behaviour every algorithm must share. Written once, run against all three — the payoff of
 * coding to the {@link RateLimiter} interface.
 */
class RateLimiterContractTest {

    private static final RateLimit FIVE_PER_SECOND = RateLimit.perSecond(5);

    private final FakeTicker ticker = new FakeTicker();

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void burstAtOneInstantGrantsExactlyTheLimit(Algorithm algorithm) {
        RateLimiter limiter = RateLimiters.create(algorithm, FIVE_PER_SECOND, ticker);

        int allowed = 0;
        for (int i = 0; i < 8; i++) {
            if (limiter.tryAcquire("alice").allowed()) {
                allowed++;
            }
        }

        assertEquals(5, allowed);
    }

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void fullLimitIsAvailableAgainAfterTwoQuietWindows(Algorithm algorithm) {
        RateLimiter limiter = RateLimiters.create(algorithm, FIVE_PER_SECOND, ticker);
        drain(limiter, "alice");

        ticker.advance(Duration.ofSeconds(2));

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("alice").allowed(), "permit " + i);
        }
        assertFalse(limiter.tryAcquire("alice").allowed());
    }

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void waitingRetryAfterIsEnoughForTheSameRequestToSucceed(Algorithm algorithm) {
        RateLimiter limiter = RateLimiters.create(algorithm, FIVE_PER_SECOND, ticker);
        drain(limiter, "alice");

        Decision denied = limiter.tryAcquire("alice");
        assertFalse(denied.allowed());
        assertTrue(denied.retryAfter().compareTo(Duration.ZERO) > 0);

        ticker.advance(denied.retryAfter());

        assertTrue(limiter.tryAcquire("alice").allowed());
    }

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void keysAreLimitedIndependently(Algorithm algorithm) {
        RateLimiter limiter = RateLimiters.create(algorithm, FIVE_PER_SECOND, ticker);
        drain(limiter, "alice");

        assertFalse(limiter.tryAcquire("alice").allowed());
        assertTrue(limiter.tryAcquire("bob").allowed());
    }

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void multiPermitRequestsAreAllOrNothing(Algorithm algorithm) {
        RateLimiter limiter = RateLimiters.create(algorithm, FIVE_PER_SECOND, ticker);
        assertTrue(limiter.tryAcquire("alice", 3).allowed());

        Decision tooMany = limiter.tryAcquire("alice", 3); // only 2 left

        assertFalse(tooMany.allowed());
        assertEquals(2, tooMany.remaining());
        assertTrue(limiter.tryAcquire("alice", 2).allowed(), "the denied call consumed nothing");
    }

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void rejectsRequestsThatCanNeverSucceed(Algorithm algorithm) {
        RateLimiter limiter = RateLimiters.create(algorithm, FIVE_PER_SECOND, ticker);

        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire("alice", 6));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire("alice", 0));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(" ", 1));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(null, 1));
    }

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void evictionOnlyDropsKeysWhoseLimitHasRecovered(Algorithm algorithm) {
        KeyedRateLimiter<?> limiter = RateLimiters.create(algorithm, FIVE_PER_SECOND, ticker);
        drain(limiter, "old");
        ticker.advance(Duration.ofSeconds(3));
        limiter.tryAcquire("fresh");

        int evicted = limiter.evictIdle();

        assertEquals(1, evicted);
        assertEquals(1, limiter.trackedKeys(), "only 'fresh' remains");
    }

    /**
     * The strongest eviction property: running the same traffic with aggressive eviction after
     * every request produces exactly the same decisions as never evicting.
     */
    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void evictionIsLossless(Algorithm algorithm) {
        FakeTicker tickerA = new FakeTicker();
        FakeTicker tickerB = new FakeTicker();
        KeyedRateLimiter<?> neverEvicts = RateLimiters.create(algorithm, FIVE_PER_SECOND, tickerA);
        KeyedRateLimiter<?> alwaysEvicts = RateLimiters.create(algorithm, FIVE_PER_SECOND, tickerB);
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

    private static void drain(RateLimiter limiter, String key) {
        while (limiter.tryAcquire(key).allowed()) {
            // consume every available permit
        }
    }
}
