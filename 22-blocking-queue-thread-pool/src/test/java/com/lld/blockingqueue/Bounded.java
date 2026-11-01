package com.lld.blockingqueue;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bounded versions of every blocking test call. A broken queue makes a thread wait forever, so each wait here gives up after 5 seconds and fails with a message that names the likely bug. A mutated queue therefore fails fast instead of hanging the build.
 */
final class Bounded {

    private static final long LIMIT_SECONDS = 5;

    /** Not meant to be created: this class only holds static helpers. */
    private Bounded() {
    }

    /** Waits up to 5 seconds for {@code permits} permits, and fails with {@code message} if they do not come. */
    static void acquire(Semaphore semaphore, int permits, String message) throws InterruptedException {
        assertTrue(semaphore.tryAcquire(permits, LIMIT_SECONDS, TimeUnit.SECONDS), message);
    }

    /** Waits up to 5 seconds for the latch, and fails with {@code message} if it never opens. */
    static void await(CountDownLatch latch, String message) throws InterruptedException {
        assertTrue(latch.await(LIMIT_SECONDS, TimeUnit.SECONDS), message);
    }

    /** Returns the future's result, waiting at most 5 seconds. A timeout fails with {@code message}. */
    static <T> T get(Future<T> future, String message) throws Exception {
        try {
            return future.get(LIMIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError(message, e);
        }
    }

    /** Waits at most 5 seconds for the future to FAIL, and returns the exception it failed with. Fails with {@code message} on a timeout or if it succeeded. */
    static Throwable failureOf(Future<?> future, String message) throws InterruptedException {
        try {
            future.get(LIMIT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (TimeoutException e) {
            throw new AssertionError(message, e);
        }
        throw new AssertionError(message + " (it succeeded instead of failing)");
    }
}
