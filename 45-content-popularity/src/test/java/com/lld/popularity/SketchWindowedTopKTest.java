package com.lld.popularity;

import static com.lld.popularity.FakeTicker.sec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Tests the bounded-memory window: heap behaviour, expiry of old sketches, memory bound and agreement with the exact version. */
class SketchWindowedTopKTest {

    private static final WindowSpec SPEC = WindowSpec.of(Duration.ofSeconds(10), Duration.ofSeconds(60));

    private final FakeTicker t = new FakeTicker();

    /** An item whose estimate rises while it is already in the heap is updated in place, never listed twice. */
    @Test
    void heapUpdatesInsteadOfDuplicating() {
        IndexedMinHeap h = new IndexedMinHeap(3);
        h.offer("a", 1);
        h.offer("b", 1);
        h.offer("a", 2);
        h.offer("a", 3);
        assertEquals(2, h.size());
        assertEquals(1, h.idList().stream().filter("a"::equals).count());
    }

    /** A full heap keeps the heaviest: a weaker newcomer is refused, a stronger one evicts the weakest. */
    @Test
    void heapKeepsHeaviest() {
        IndexedMinHeap h = new IndexedMinHeap(2);
        h.offer("a", 5);
        h.offer("b", 3);
        h.offer("c", 1);
        assertEquals("b", h.weakest());
        h.offer("d", 4);
        assertEquals(List.of("a", "d"), h.idList().stream().sorted().toList());
    }

    /** Equal counts: the larger id is the weaker one, matching the tie rule of the exact version. */
    @Test
    void heapTieEvictsLargerId() {
        IndexedMinHeap h = new IndexedMinHeap(2);
        h.offer("b", 2);
        h.offer("c", 2);
        h.offer("a", 2);
        assertEquals(List.of("a", "b"), h.idList().stream().sorted().toList());
    }

    /** Events in a bucket that left the window no longer count, and the reused slot starts clean after a long gap. */
    @Test
    void expiryAndStaleSketchReset() {
        SketchWindowedTopK s = new SketchWindowedTopK(t, SPEC, 0.001, 0.01, 5, 1);
        t.setSeconds(1);
        for (int i = 0; i < 4; i++) {
            s.record("old");
        }
        t.setSeconds(65);
        assertEquals(List.of(), s.topK(3));
        t.setSeconds(1 + 60 * 5);
        s.record("new");
        assertEquals(List.of(new Ranked("new", 1)), s.topK(3));
        assertEquals(0, s.estimate("old"));
    }

    /** Counts from several live buckets are added together. */
    @Test
    void sumsAcrossBuckets() {
        SketchWindowedTopK s = new SketchWindowedTopK(t, SPEC, 0.001, 0.01, 5, 1);
        for (int b = 0; b < 4; b++) {
            t.setSeconds(b * 10L);
            s.record("a");
            s.record("a");
        }
        assertEquals(List.of(new Ranked("a", 8)), s.topK(1));
    }

    /** With a roomy sketch and heap the answer equals the exact version on random streams with idle gaps; the sketch is never below exact for any returned item. */
    @Test
    void agreesWithExactVersion() {
        for (long seed = 1; seed <= 10; seed++) {
            Random rnd = new Random(seed);
            FakeTicker c = new FakeTicker();
            WindowedTopK exact = new WindowedTopK(c, SPEC);
            SketchWindowedTopK sk = new SketchWindowedTopK(c, SPEC, 0.0005, 0.001, 20, seed);
            for (int i = 0; i < 500; i++) {
                c.advanceSeconds(rnd.nextInt(10) == 0 ? 30 + rnd.nextInt(100) : rnd.nextInt(5));
                String id = "i" + rnd.nextInt(10);
                exact.record(id);
                sk.record(id);
                if (i % 7 == 0) {
                    assertEquals(exact.topK(5), sk.topK(5), "seed " + seed + " i " + i);
                }
            }
        }
    }

    /** Memory is fixed by the settings: buckets * depth * width * 8 bytes, however many distinct ids arrive. */
    @Test
    void memoryDoesNotGrowWithDistinctItems() {
        SketchWindowedTopK s = new SketchWindowedTopK(t, SPEC, 0.01, 0.01, 10, 1);
        long before = s.counterBytes();
        assertEquals(6L * 5 * 272 * 8, before);
        for (int i = 0; i < 50_000; i++) {
            s.record("id" + i);
        }
        assertEquals(before, s.counterBytes());
        assertTrue(s.topK(100).size() <= 10);
    }

    /** A late event older than the window is rejected and must not wipe or touch the live bucket that shares its slot. */
    @Test
    void lateEventRejectedAndLiveBucketSurvives() {
        SketchWindowedTopK s = new SketchWindowedTopK(t, SPEC, 0.001, 0.01, 5, 1);
        t.setSeconds(100);
        s.record("live");
        assertFalse(s.recordAt("late", sec(45)));
        assertEquals(List.of(new Ranked("live", 1)), s.topK(3));
        assertTrue(s.recordAt("ok", sec(55)));
    }

    /** A future timestamp throws, even one second ahead inside the current bucket, and in the next bucket. */
    @Test
    void futureEventThrows() {
        SketchWindowedTopK s = new SketchWindowedTopK(t, SPEC, 0.001, 0.01, 5, 1);
        t.setSeconds(100);
        assertThrows(IllegalArgumentException.class, () -> s.recordAt("a", sec(105)));
        assertThrows(IllegalArgumentException.class, () -> s.recordAt("a", sec(115)));
        assertEquals(List.of(), s.topK(3));
    }

    /** Raising a heap item's count and then offering a rival: the heap must re-sort after an update, so the weakest is evicted, not the one that just grew. */
    @Test
    void heapResortsAfterUpdate() {
        IndexedMinHeap h = new IndexedMinHeap(2);
        h.offer("a", 1);
        h.offer("b", 2);
        h.offer("a", 5);
        h.offer("c", 3);
        assertEquals(List.of("a", "c"), h.idList().stream().sorted().toList());
    }

    /** The documented limit: with a heap of 2 per bucket, an item that is medium in every bucket (m, 2 per bucket) is crowded out by two heavier ids per bucket, so the exact answer is m (6) but the sketch answer misses it. */
    @Test
    void mediumEverywhereItemIsMissedWhenHeapIsSmall() {
        WindowSpec spec = new WindowSpec(sec(10), 3);
        SketchWindowedTopK sk = new SketchWindowedTopK(t, spec, 0.0005, 0.001, 2, 1);
        WindowedTopK exact = new WindowedTopK(t, spec);
        for (int b = 0; b < 3; b++) {
            t.setSeconds(b * 10L);
            for (String id : new String[] {"X" + b, "P" + b}) {
                for (int i = 0; i < 3; i++) {
                    sk.record(id);
                    exact.record(id);
                }
            }
            for (int i = 0; i < 2; i++) {
                sk.record("m");
                exact.record("m");
            }
        }
        assertEquals(List.of(new Ranked("m", 6)), exact.topK(1));
        assertTrue(sk.topK(1).stream().noneMatch(r -> r.id().equals("m")));
        assertEquals(6, sk.estimate("m"));
    }

    /** With far fewer heap slots (2) than ids (10), a clearly hot item still wins, so eviction in the heap is exercised without losing the head. */
    @Test
    void hotItemSurvivesHeapEviction() {
        SketchWindowedTopK s = new SketchWindowedTopK(t, SPEC, 0.001, 0.01, 2, 1);
        for (int i = 0; i < 40; i++) {
            s.record("id" + i % 10);
            s.record("hot");
        }
        assertEquals("hot", s.topK(1).get(0).id());
        assertEquals(2, s.topK(10).size());
    }

    /** The lock is really held while the tracker reads its ticker. */
    @Test
    void lockIsHeldDuringOperations() {
        List<Boolean> held = new ArrayList<>();
        SketchWindowedTopK[] box = new SketchWindowedTopK[1];
        box[0] = new SketchWindowedTopK(() -> {
            if (box[0] != null) {
                held.add(Thread.holdsLock(box[0]));
            }
            return 0L;
        }, SPEC, 0.01, 0.01, 3, 1);
        box[0].record("a");
        box[0].recordAt("a", 0L);
        box[0].topK(1);
        box[0].estimate("a");
        assertEquals(5, held.size());
        assertFalse(held.contains(false));
    }
}
