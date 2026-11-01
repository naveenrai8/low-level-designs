package com.lld.kvstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Tests the concurrency model: one session per thread, atomic outermost commit, first committer wins. */
@Timeout(30)
class ConcurrencyTest {

    private final ManualClock clock = new ManualClock();
    private final InMemoryKvStore store = new InMemoryKvStore(clock);

    /** Forced interleaving on one thread: two sessions begin, both write the same key, the second to commit loses with a ConflictException. */
    @Test
    void firstCommitterWins() {
        store.set("k", "0");
        Session a = store.openSession();
        Session b = store.openSession();
        a.begin();
        b.begin();
        a.set("k", "from-a");
        b.set("k", "from-b");
        b.commit();
        assertThrows(ConflictException.class, a::commit);
        assertEquals(Optional.of("from-b"), store.get("k"));
        assertEquals(0, a.depth());
    }

    /** Transactions that touch different keys do not conflict, even when they overlap in time. */
    @Test
    void disjointKeysDoNotConflict() {
        Session a = store.openSession();
        Session b = store.openSession();
        a.begin();
        b.begin();
        a.set("x", "1");
        b.set("y", "2");
        b.commit();
        a.commit();
        assertEquals(2, store.count());
    }

    /** A key expiring (a store-made tombstone) is not mistaken for a competing user write. */
    @Test
    void expiryIsNotAConflict() {
        store.set("k", "v", Duration.ofSeconds(1));
        Session a = store.openSession();
        a.begin();
        a.set("k", "mine");
        clock.advance(Duration.ofSeconds(2));
        store.get("k");
        a.commit();
        assertEquals(Optional.of("mine"), store.get("k"));
    }

    /** An expiry tombstone must not hide a real user write made after it: A began before the expiry, B wrote the key afterwards, so A's commit conflicts. */
    @Test
    void userWriteAfterExpiryStillConflicts() {
        store.set("k", "v", Duration.ofSeconds(1));
        Session a = store.openSession();
        a.begin();
        clock.advance(Duration.ofSeconds(2));
        store.get("k");
        store.set("k", "b");
        a.set("k", "a");
        assertThrows(ConflictException.class, a::commit);
        assertEquals(Optional.of("b"), store.get("k"));
    }

    /** A transaction's writes become visible all at once: another reader sees none of them before commit and all of them after. */
    @Test
    void outerCommitIsAtomicToReaders() {
        Session a = store.openSession();
        a.begin();
        a.set("x", "1");
        a.set("y", "2");
        assertEquals(0, store.count());
        a.commit();
        assertEquals(2, store.count());
    }

    /** Many threads each retry a read-modify-write transaction on one counter; first-committer-wins plus retry gives the exact total, with no lost update. */
    @Test
    void contendedCounterHasExactTotal() throws Exception {
        int threads = 8;
        int perThread = 200;
        store.set("n", "0");
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    Session s = store.openSession();
                    gate.await();
                    for (int i = 0; i < perThread; i++) {
                        while (true) {
                            s.begin();
                            s.set("n", Integer.toString(Integer.parseInt(s.get("n").orElseThrow()) + 1));
                            try {
                                s.commit();
                                break;
                            } catch (ConflictException retry) {
                                // lost the race: loop and run the transaction again on fresh data
                            }
                        }
                    }
                    return null;
                }));
            }
            gate.countDown();
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(Optional.of(Integer.toString(threads * perThread)), store.get("n"));
        assertEquals(1, store.versionCount());
    }

    /** Threads writing their own keys through transactions all succeed and the exact key count appears. */
    @Test
    void parallelDisjointTransactionsKeepEverything() throws Exception {
        int threads = 8;
        int perThread = 100;
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int id = t;
                futures.add(pool.submit(() -> {
                    Session s = store.openSession();
                    gate.await();
                    for (int i = 0; i < perThread; i++) {
                        s.begin();
                        s.set("t" + id + "-" + i, "v");
                        s.commit();
                    }
                    return null;
                }));
            }
            gate.countDown();
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(threads * perThread, store.count());
    }
}
