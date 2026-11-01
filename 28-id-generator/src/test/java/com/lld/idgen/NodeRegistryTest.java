package com.lld.idgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Proves the node-number assignment seam (F3), including the quarantine that stops a released number being reused too soon. */
class NodeRegistryTest {

    private static final long START = 1_700_000_001_000L;

    /** Different owners get different numbers, lowest first. */
    @Test
    void ownersGetDistinctNumbers() {
        InMemoryNodeRegistry r = new InMemoryNodeRegistry(new FakeClock(START), 0);
        assertEquals(0, r.acquire("a"));
        assertEquals(1, r.acquire("b"));
    }

    /** Asking twice with the same owner returns the same number, so a retried start-up is safe. */
    @Test
    void acquireIsIdempotentPerOwner() {
        InMemoryNodeRegistry r = new InMemoryNodeRegistry(new FakeClock(START), 0);
        assertEquals(r.acquire("a"), r.acquire("a"));
        assertEquals(1, r.acquire("b"));
    }

    /** Quarantine boundary: with a 5 ms quarantine a number released at T is unavailable at T and T+5, and free again at T+6. */
    @Test
    void releasedNumberIsQuarantinedUntilExactBoundary() {
        FakeClock clock = new FakeClock(START);
        InMemoryNodeRegistry r = new InMemoryNodeRegistry(clock, 5);
        assertEquals(0, r.acquire("a"));
        r.release("a");
        assertEquals(1, r.acquire("b")); // 0 is quarantined, so b gets 1
        r.release("b");
        clock.set(START + 5);
        assertEquals(2, r.acquire("c")); // 0 and 1 still quarantined at release + 5
        clock.set(START + 6);
        assertEquals(0, r.acquire("d")); // 0 is free again at release + 6
    }

    /** A new generator on a number released in the same millisecond would repeat IDs; the quarantine makes the registry hand out another number instead. */
    @Test
    void sameMillisecondReuseIsPrevented() {
        FakeClock clock = new FakeClock(START);
        InMemoryNodeRegistry r = new InMemoryNodeRegistry(clock, 0);
        int oldNode = r.acquire("old");
        long oldId = new SnowflakeIdGenerator(clock, Waiter.spin(), oldNode, START - 1000, 0).nextId();
        r.release("old");
        int newNode = r.acquire("new");
        long newId = new SnowflakeIdGenerator(clock, Waiter.spin(), newNode, START - 1000, 0).nextId();
        assertNotEquals(oldNode, newNode);
        assertNotEquals(oldId, newId);
    }

    /** After release the owner forgets its number: when someone else takes it, the old owner asking again must not get the same number. */
    @Test
    void releasedOwnerDoesNotGetItsOldNumberBack() {
        FakeClock clock = new FakeClock(START);
        InMemoryNodeRegistry r = new InMemoryNodeRegistry(clock, 0);
        assertEquals(0, r.acquire("a"));
        r.release("a");
        clock.advance(1);
        assertEquals(0, r.acquire("c"));
        assertNotEquals(0, r.acquire("a"));
    }

    /** After all 1024 numbers are taken the next owner is refused, and releasing a stranger changes nothing. */
    @Test
    void exhaustionThrows() {
        InMemoryNodeRegistry r = new InMemoryNodeRegistry(new FakeClock(START), 0);
        for (int i = 0; i <= SnowflakeLayout.MAX_NODE; i++) {
            assertEquals(i, r.acquire("o" + i));
        }
        r.release("stranger");
        assertThrows(IllegalStateException.class, () -> r.acquire("extra"));
    }

    /** 16 threads released by a start gate acquire 60 numbers each: all 960 numbers handed out are distinct. */
    @Test
    @Timeout(20)
    void concurrentAcquireHandsOutDistinctNumbers() throws Exception {
        InMemoryNodeRegistry r = new InMemoryNodeRegistry(new FakeClock(START), 0);
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<List<Integer>>> futures = new ArrayList<>();
            for (int t = 0; t < 16; t++) {
                int id = t;
                futures.add(pool.submit(() -> {
                    gate.await();
                    List<Integer> mine = new ArrayList<>();
                    for (int i = 0; i < 60; i++) {
                        mine.add(r.acquire("t" + id + "-" + i));
                    }
                    return mine;
                }));
            }
            gate.countDown();
            Set<Integer> all = new HashSet<>();
            for (Future<List<Integer>> f : futures) {
                all.addAll(f.get(15, TimeUnit.SECONDS));
            }
            assertEquals(960, all.size());
            assertTrue(all.stream().allMatch(n -> n >= 0 && n <= SnowflakeLayout.MAX_NODE));
        } finally {
            pool.shutdownNow();
        }
    }

    /** Two generators on numbers from the registry never produce the same ID, even on the same millisecond. */
    @Test
    void generatorsWithRegistryNodesNeverCollide() {
        FakeClock clock = new FakeClock(START);
        InMemoryNodeRegistry r = new InMemoryNodeRegistry(clock, 0);
        SnowflakeIdGenerator a = new SnowflakeIdGenerator(clock, Waiter.spin(), r.acquire("a"), 1_700_000_000_000L, 0);
        SnowflakeIdGenerator b = new SnowflakeIdGenerator(clock, Waiter.spin(), r.acquire("b"), 1_700_000_000_000L, 0);
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seen.add(a.nextId());
            seen.add(b.nextId());
        }
        assertEquals(2000, seen.size());
    }
}
