package com.lld.ratelimiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The behaviour that distinguishes the algorithms — what you'd explain when asked "why this one?". */
class AlgorithmBehaviourTest {

    private final FakeTicker ticker = new FakeTicker();

    @Nested
    class TokenBucketBehaviour {

        // 10/s => one token every 100ms
        private final RateLimiter limiter =
                new KeyedRateLimiter<>(new TokenBucket(RateLimit.perSecond(10)), ticker);

        @ParameterizedTest(name = "after draining, wait {0}ms -> {1} tokens")
        @CsvSource({"0, 0", "99, 0", "100, 1", "250, 2", "1000, 10", "3600000, 10"})
        void refillsLinearlyAndCapsAtCapacity(long waitMillis, int expectedTokens) {
            drain(limiter);
            ticker.advanceMillis(waitMillis);

            int granted = 0;
            while (limiter.tryAcquire("k").allowed()) {
                granted++;
            }

            assertEquals(expectedTokens, granted);
        }

        @Test
        void retryAfterIsTimeUntilTheNextToken() {
            drain(limiter);
            ticker.advanceMillis(30);

            Decision d = limiter.tryAcquire("k");

            assertFalse(d.allowed());
            assertEquals(Duration.ofMillis(70), d.retryAfter());
        }

        @Test
        void partialProgressTowardTheNextTokenIsKept() {
            drain(limiter);
            ticker.advanceMillis(150); // 1 token + 50ms of progress
            assertTrue(limiter.tryAcquire("k").allowed());

            ticker.advanceMillis(50); // the 50ms carried over completes token #2

            assertTrue(limiter.tryAcquire("k").allowed(),
                    "advancing the refill point to 'now' instead would lose the 50ms");
        }

        @Test
        void isNotAPerWindowCapItAllowsNearlyTwiceTheLimitWithinOneWindow() {
            drain(limiter); // 10 at t=0 (the full burst capacity)
            int granted = 10;
            for (int t = 100; t <= 900; t += 100) {
                ticker.advanceMillis(100);
                if (limiter.tryAcquire("k").allowed()) {
                    granted++;
                }
            }

            assertEquals(19, granted, "capacity + rate * (window - 1 token interval), all within 1s");
        }
    }

    @Nested
    class SlidingWindowLogBehaviour {

        private final RateLimiter limiter =
                new KeyedRateLimiter<>(new SlidingWindowLog(RateLimit.perSecond(5)), ticker);

        @Test
        void hasNoBoundaryBurst() {
            // A fixed 1s window would allow 5 at t=900ms and 5 more at t=1100ms: 10 in 200ms.
            ticker.advanceMillis(900);
            drain(limiter);

            ticker.advanceMillis(200); // t = 1100ms

            assertFalse(limiter.tryAcquire("k").allowed());
        }

        @Test
        void aGrantStopsCountingExactlyOneWindowLater() {
            drain(limiter);

            ticker.advanceMillis(999);
            assertFalse(limiter.tryAcquire("k").allowed());

            ticker.advanceMillis(1);
            assertTrue(limiter.tryAcquire("k").allowed());
        }

        @Test
        void retryAfterWaitsForTheLastGrantThatMustExpire() {
            limiter.tryAcquire("k", 2); // grants at t=0
            ticker.advanceMillis(400);
            limiter.tryAcquire("k", 3); // grants at t=400; now full

            // 2 permits: both t=0 grants must expire -> at t=1000, i.e. 600ms from now.
            assertEquals(Duration.ofMillis(600), limiter.tryAcquire("k", 2).retryAfter());
            // 3 permits: one t=400 grant must expire too -> at t=1400, i.e. 1000ms from now.
            assertEquals(Duration.ofMillis(1_000), limiter.tryAcquire("k", 3).retryAfter());
        }
    }

    @Nested
    class SlidingWindowCounterBehaviour {

        private final RateLimiter limiter =
                new KeyedRateLimiter<>(new SlidingWindowCounter(RateLimit.perSecond(10)), ticker);

        @Test
        void weightsThePreviousWindowByItsRemainingOverlap() {
            drain(limiter); // 10 in window #1
            ticker.advanceMillis(1_500); // halfway through window #2: estimate = 10 * 0.5 = 5

            int granted = 0;
            while (limiter.tryAcquire("k").allowed()) {
                granted++;
            }

            assertEquals(5, granted);
        }

        @Test
        void retryAfterSolvesTheEstimateFormula() {
            drain(limiter);
            ticker.advanceMillis(1_000); // start of window #2: estimate = 10 * 1.0 = 10

            Decision d = limiter.tryAcquire("k");

            // 10 * (1000 - e)/1000 + 1 <= 10  =>  e >= 100ms
            assertFalse(d.allowed());
            assertEquals(Duration.ofMillis(100), d.retryAfter());
        }

        /**
         * The approximation assumes the previous window's requests were evenly spread. Cluster
         * them at its very end and a true 1s window can see nearly 2x the limit.
         */
        @Test
        void isApproximateClusteredTrafficCanNearlyDoubleTheLimit() {
            limiter.tryAcquire("k"); // t=0 opens window #1 = [0, 1000)
            ticker.advanceMillis(999);
            int cluster = 0;
            while (limiter.tryAcquire("k").allowed()) {
                cluster++; // 9 more at t=999
            }

            int windowTwo = 0;
            ticker.advanceMillis(151); // t=1150
            for (int i = 0; i < 9; i++) { // t=1150, 1250, ..., 1950
                if (limiter.tryAcquire("k").allowed()) {
                    windowTwo++;
                }
                ticker.advanceMillis(100);
            }

            // Every one of these lies in the true sliding window (998ms, 1998ms].
            assertEquals(9, cluster);
            assertEquals(9, windowTwo);
            assertEquals(18, cluster + windowTwo, "1.8x a limit of 10 inside one real second");
        }
    }

    private static void drain(RateLimiter limiter) {
        while (limiter.tryAcquire("k").allowed()) {
            // consume every available permit
        }
    }
}
