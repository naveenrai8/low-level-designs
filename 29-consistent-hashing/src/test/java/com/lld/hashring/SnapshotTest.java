package com.lld.hashring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Proves membership changes are published as whole immutable snapshots, so readers never see a torn ring. */
class SnapshotTest {

    /** A held snapshot keeps answering from the old membership after the live locator changes, and new reads see the new one. */
    @ParameterizedTest
    @EnumSource(LocatorFactory.class)
    void heldSnapshotIsFrozen(LocatorFactory f) {
        Locator l = f.create(1);
        l.addNode("a");
        l.addNode("b");
        Lookup old = l.snapshot();
        l.addNode("c");
        l.removeNode("a");
        assertEquals(Set.of("a", "b"), old.nodes());
        assertEquals(Set.of("b", "c"), l.nodes());
        for (int i = 0; i < 200; i++) {
            assertTrue(Set.of("a", "b").contains(old.locate("k" + i)));
            assertTrue(Set.of("b", "c").contains(l.locate("k" + i)));
        }
    }

    /** With no change in between, snapshot() returns the same object, so readers pay no copying cost. */
    @ParameterizedTest
    @EnumSource(LocatorFactory.class)
    void snapshotIsStableObject(LocatorFactory f) {
        Locator l = f.create(1);
        l.addNode("a");
        assertSame(l.snapshot(), l.snapshot());
    }

    /** Re-adding a node with the same weight, or removing a missing one, publishes nothing. */
    @ParameterizedTest
    @EnumSource(LocatorFactory.class)
    void noOpChangesDoNotPublish(LocatorFactory f) {
        Locator l = f.create(1);
        l.addNode("a", 2);
        Lookup s = l.snapshot();
        l.addNode("a", 2);
        l.removeNode("zzz");
        assertSame(s, l.snapshot());
    }

    /**
     * Freezes a writer after it has built the new ring but before it publishes it. A reader on another thread must still see the complete old ring; once the writer is released it sees the complete new ring.
     *
     * <p>The two latches force the order, so no sleeping is needed.
     */
    @Test
    void readerNeverSeesHalfPublishedRing() throws Exception {
        ConsistentHashRing ring = new ConsistentHashRing(50, 2);
        ring.addNode("a");
        ring.addNode("b");
        CountDownLatch built = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ring.setBeforePublish(() -> {
            built.countDown();
            await(release);
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = pool.submit(() -> ring.addNode("c"));
            assertTrue(built.await(5, TimeUnit.SECONDS));
            Future<Set<String>> reader = pool.submit(() -> {
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < 2000; i++) {
                    seen.add(ring.locate("k" + i));
                }
                return seen;
            });
            assertEquals(Set.of("a", "b"), reader.get(5, TimeUnit.SECONDS));
            assertEquals(Set.of("a", "b"), ring.nodes());
            release.countDown();
            writer.get(5, TimeUnit.SECONDS);
            assertEquals(Set.of("a", "b", "c"), ring.nodes());
            Set<String> after = new HashSet<>();
            for (int i = 0; i < 2000; i++) {
                after.add(ring.locate("k" + i));
            }
            assertEquals(Set.of("a", "b", "c"), after);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** Waits on a latch, turning an interrupt into an unchecked error. */
    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Two writers race: both build from the same old state, one is frozen before publishing while the other finishes. The frozen one must retry from the winner's state, so both nodes end up present (no lost update). */
    @Test
    void racingWritersLoseNoUpdate() throws Exception {
        ConsistentHashRing ring = new ConsistentHashRing(10, 2);
        ring.addNode("base");
        CountDownLatch firstBuilt = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ring.setBeforePublish(() -> {
            // Only the very first publish attempt (writer one) is frozen; later attempts run straight through.
            if (calls.incrementAndGet() == 1) {
                firstBuilt.countDown();
                await(secondDone);
            }
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> one = pool.submit(() -> ring.addNode("one"));
            assertTrue(firstBuilt.await(5, TimeUnit.SECONDS));
            Future<?> two = pool.submit(() -> ring.addNode("two"));
            two.get(5, TimeUnit.SECONDS);
            assertFalse(ring.nodes().contains("one"));
            secondDone.countDown();
            one.get(5, TimeUnit.SECONDS);
            assertEquals(Set.of("base", "one", "two"), ring.nodes());
        } finally {
            secondDone.countDown();
            pool.shutdownNow();
        }
    }

    /** Rendezvous replication: the preference list is the top-N scores, distinct, and starts with the single owner. */
    @Test
    void rendezvousPreferenceListIsDistinct() {
        RendezvousLocator l = new RendezvousLocator(4);
        for (int i = 0; i < 5; i++) {
            l.addNode("n" + i);
        }
        for (int i = 0; i < 300; i++) {
            List<String> p = l.locate("k" + i, 3);
            assertEquals(3, new HashSet<>(p).size());
            assertEquals(l.locate("k" + i), p.get(0));
        }
        assertEquals(5, l.locate("x", 99).size());
    }
}
