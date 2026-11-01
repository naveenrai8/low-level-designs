package com.lld.ratelimiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.temporal.Temporal;

import com.lld.ratelimiter.algorithm.SlidingWindowCounter;
import com.lld.ratelimiter.algorithm.SlidingWindowLog;
import com.lld.ratelimiter.algorithm.TokenBucket;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The behaviour that makes each algorithm different: what you'd explain when asked "why this one?". */
class AlgorithmBehaviourTest {

    private final FakeTicker ticker = new FakeTicker();

    /** Token bucket at 10 per second: a burst of 10, then one new token every 100 ms. */
    @Nested
    class TokenBucketBehaviour {

        // 10/s => one token every 100ms
        private final RateLimiter limiter =
                new KeyedRateLimiter<>(new TokenBucket(RateLimit.perSecond(10)), ticker);

        /** Proves an empty bucket gains one token per 100 ms of waiting and never holds more than 10. */
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

        /** Proves a denied request is told to wait exactly until the next token arrives (70 ms after waiting 30 ms). */
        @Test
        void retryAfterIsTimeUntilTheNextToken() {
            drain(limiter);
            ticker.advanceMillis(30);

            Decision d = limiter.tryAcquire("k");

            assertFalse(d.allowed());
            assertEquals(Duration.ofMillis(70), d.retryAfter());
        }

        /** Proves time already spent toward the next token is not thrown away when a refill happens. */
        @Test
        void partialProgressTowardTheNextTokenIsKept() {
            drain(limiter);
            ticker.advanceMillis(150); // 1 token + 50ms of progress
            assertTrue(limiter.tryAcquire("k").allowed());

            ticker.advanceMillis(50); // the 50ms carried over completes token #2

            assertTrue(limiter.tryAcquire("k").allowed(),
                    "advancing the refill point to 'now' instead would lose the 50ms");
        }

        /** Proves a token bucket is not a "per window" cap: a full burst plus the refill lets 19 through inside one second. */
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

    /** Sliding-window log at 5 per second: exact, because it remembers every grant time. */
    @Nested
    class SlidingWindowLogBehaviour {

        private final RateLimiter limiter =
                new KeyedRateLimiter<>(new SlidingWindowLog(RateLimit.perSecond(5)), ticker);

        /** Proves there is no double burst at a window edge, unlike a fixed window. */
        @Test
        void hasNoBoundaryBurst() {
            // A fixed 1s window would allow 5 at t=900ms and 5 more at t=1100ms: 10 in 200ms.
            ticker.advanceMillis(900);
            drain(limiter);

            ticker.advanceMillis(200); // t = 1100ms

            assertFalse(limiter.tryAcquire("k").allowed());
        }

        /** Proves a grant still counts 999 ms later and stops counting at exactly 1000 ms. */
        @Test
        void aGrantStopsCountingExactlyOneWindowLater() {
            drain(limiter);

            ticker.advanceMillis(999);
            assertFalse(limiter.tryAcquire("k").allowed());

            ticker.advanceMillis(1);
            assertTrue(limiter.tryAcquire("k").allowed());
        }

        /** Proves the wait is set by the last old grant that must leave the window to make room for the request. */
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

    /** Sliding-window counter at 10 per second: an estimate built from this window's count and a fading share of the last one. */
    @Nested
    class SlidingWindowCounterBehaviour {

        private final RateLimiter limiter =
                new KeyedRateLimiter<>(new SlidingWindowCounter(RateLimit.perSecond(10)), ticker);

        /** Proves halfway through the next window, the previous window's 10 requests count as 5, so 5 more are allowed. */
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

        /** Proves the wait is found by solving the estimate formula: 100 ms until one request fits again. */
        @Test
        void retryAfterSolvesTheEstimateFormula() {
            drain(limiter);
            ticker.advanceMillis(1_000); // start of window #2: estimate = 10 * 1.0 = 10

            Decision d = limiter.tryAcquire("k");

            // 10 * (1000 - e)/1000 + 1 <= 10  =>  e >= 100ms
            assertFalse(d.allowed());
            assertEquals(Duration.ofMillis(100), d.retryAfter());
        }

        /** Proves the estimate can be fooled: requests bunched at the end of one window let nearly 2× the limit into one real second. */
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

    /** Uses up every permit key "k" has right now, so a test can start from empty. */
    private static void drain(RateLimiter limiter) {
        while (limiter.tryAcquire("k").allowed()) {
            // consume every available permit
        }
    }
}
