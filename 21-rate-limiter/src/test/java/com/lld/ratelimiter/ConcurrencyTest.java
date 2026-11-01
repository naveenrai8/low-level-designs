package com.lld.ratelimiter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lld.ratelimiter.RateLimiterFactory.Algorithm;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Proves "check and take" happens as one step when many threads race. Time is frozen, so no permits refill during the race: the only correct result is exactly {@code limit} successes per key.
 *
 * <p>Measured against a check-then-act version ({@code get}, compare, {@code put}; each call is safe on its own, but the sequence is not): 19 of 20 runs gave out too many permits on an Apple-silicon laptop, from 1,004 up to all 6,400 attempts. Lost updates keep the counter low, so the over-grant has no upper bound; it is not just "a few extra".
 */
class ConcurrencyTest {

    private static final int THREADS = 32;
    private static final int ATTEMPTS_PER_THREAD = 200;
    private static final RateLimit LIMIT = RateLimit.perMinute(1_000);

    /** Proves 32 threads racing on one key get exactly 1,000 permits in total, never more. */
    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void racingCallersOnOneKeyGetExactlyTheLimit(Algorithm algorithm) throws Exception {
        RateLimiter limiter = RateLimiterFactory.create(algorithm, LIMIT, new FakeTicker());

        int granted = race(limiter, threadIndex -> "hot-key");

        assertEquals(1_000, granted);
    }

    /** Proves threads racing across 8 keys get exactly 100 permits per key, so keys never leak permits to each other. */
    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void racingCallersOnManyKeysGetExactlyTheLimitPerKey(Algorithm algorithm) throws Exception {
        RateLimiter limiter = RateLimiterFactory.create(algorithm, RateLimit.perMinute(100), new FakeTicker());

        int granted = race(limiter, threadIndex -> "key-" + (threadIndex % 8));

        assertEquals(8 * 100, granted);
    }

    /** Picks which key each racing thread uses. */
    private interface KeyChooser {
        /** Returns the key that thread number {@code threadIndex} will hammer. */
        String keyFor(int threadIndex);
    }

    /** Starts all threads behind one gate so they hit the limiter at the same moment, and returns how many permits they got in total. */
    private static int race(RateLimiter limiter, KeyChooser keys) throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                String key = keys.keyFor(t);
                futures.add(pool.submit(() -> {
                    startGate.await();
                    for (int i = 0; i < ATTEMPTS_PER_THREAD; i++) {
                        if (limiter.tryAcquire(key).allowed()) {
                            granted.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            startGate.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS); // rethrows any assertion/exception from a worker
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        return granted.get();
    }
}
