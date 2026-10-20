package com.lld.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

/** Runs N tasks that all start at the same instant (a start gate), then waits for all of them. */
final class Race {

    private Race() {
    }

    /** @return the first exception any worker threw, or null */
    static Throwable run(int threads, IntFunction<Runnable> taskForThread) throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Runnable task = taskForThread.apply(t);
                futures.add(pool.submit(() -> {
                    try {
                        startGate.await();
                        task.run();
                    } catch (Throwable e) {
                        firstFailure.compareAndSet(null, e);
                    }
                    return null;
                }));
            }
            startGate.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        return firstFailure.get();
    }
}
