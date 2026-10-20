package com.lld.ratelimiter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lld.ratelimiter.RateLimiters.Algorithm;
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
 * Proves the check-and-consume is atomic. Time is frozen, so no permits refill during the race:
 * the only correct outcome is that exactly {@code limit} acquisitions succeed per key.
 *
 * <p>Measured against a check-then-act version ({@code get}, compare, {@code put} — each call
 * atomic, the sequence not): 19 of 20 runs over-granted on an Apple-silicon laptop, from 1,004 up
 * to all 6,400 attempts. Lost updates keep the counter low, so the over-grant is unbounded, not
 * "a few extra".
 */
class ConcurrencyTest {

    private static final int THREADS = 32;
    private static final int ATTEMPTS_PER_THREAD = 200;
    private static final RateLimit LIMIT = RateLimit.perMinute(1_000);

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void racingCallersOnOneKeyGetExactlyTheLimit(Algorithm algorithm) throws Exception {
        RateLimiter limiter = RateLimiters.create(algorithm, LIMIT, new FakeTicker());

        int granted = race(limiter, threadIndex -> "hot-key");

        assertEquals(1_000, granted);
    }

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void racingCallersOnManyKeysGetExactlyTheLimitPerKey(Algorithm algorithm) throws Exception {
        RateLimiter limiter = RateLimiters.create(algorithm, RateLimit.perMinute(100), new FakeTicker());

        int granted = race(limiter, threadIndex -> "key-" + (threadIndex % 8));

        assertEquals(8 * 100, granted);
    }

    private interface KeyChooser {
        String keyFor(int threadIndex);
    }

    /** Starts all threads behind one gate so they hit the limiter at the same moment. */
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
