package com.lld.mistakes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;

/**
 * Proves why a read lock cannot be upgraded, and that re-checking after release makes the lazy cache load once.
 */
class ReadWriteLockTest {

    /**
     * Proves that a thread holding the read lock is never granted the write lock, while the other direction (write to read, a downgrade) works.
     * <p>{@code writeLock().lock()} would block forever here, because the write lock waits for all readers, including this thread. {@code tryLock} shows it without hanging the test.
     */
    @Test
    void bugUpgradingReadToWriteBlocksEvenInOneThread() {
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        lock.readLock().lock();
        try {
            // writeLock().lock() here would block forever: the write lock waits for all readers, including this thread. tryLock shows it without hanging the test.
            assertFalse(lock.writeLock().tryLock(), "read -> write upgrade is never granted");
        } finally {
            lock.readLock().unlock();
        }
        // Downgrade is the supported direction: holding write, you may take read.
        lock.writeLock().lock();
        try {
            assertTrue(lock.readLock().tryLock());
            lock.readLock().unlock();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Proves that without a re-check, two threads that both missed both load the value.
     */
    @Test
    void bugReleaseThenAcquireWithoutRecheckLoadsTwice() throws Exception {
        assertEquals(2, loadsWhenTwoThreadsMissTogether(new NoRecheckLazyCache<>(Barriers.meetIn(2))));
    }

    /**
     * Proves that with a re-check under the write lock, the value is loaded once.
     */
    @Test
    void fixRecheckUnderTheWriteLockLoadsOnce() throws Exception {
        assertEquals(1, loadsWhenTwoThreadsMissTogether(new RecheckingLazyCache<>(Barriers.meetIn(2))));
    }

    /**
     * Runs two threads on one key and counts how many times the loader ran. Both miss under the read lock and meet in the gap, so both reach the write lock with a stale miss.
     */
    private static int loadsWhenTwoThreadsMissTogether(LazyCache<String, String> cache) throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CompletableFuture<?> a = CompletableFuture.runAsync(() -> cache.get("k", k -> "v" + loads.incrementAndGet()));
        CompletableFuture<?> b = CompletableFuture.runAsync(() -> cache.get("k", k -> "v" + loads.incrementAndGet()));
        CompletableFuture.allOf(a, b).get(10, TimeUnit.SECONDS);
        return loads.get();
    }
}
