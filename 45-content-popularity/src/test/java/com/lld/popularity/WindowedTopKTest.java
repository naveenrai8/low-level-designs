package com.lld.popularity;

import static com.lld.popularity.FakeTicker.sec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Tests the exact sliding-window top-K: window edges, stale buckets, ties, edge values and a differential check against a brute-force model. */
class WindowedTopKTest {

    private static final WindowSpec SPEC = WindowSpec.of(Duration.ofSeconds(10), Duration.ofSeconds(60));

    private final FakeTicker t = new FakeTicker();
    private final WindowedTopK top = new WindowedTopK(t, SPEC);

    /** Counts and ranks events recorded in the same bucket. */
    @Test
    void ranksByCount() {
        top.record("a");
        top.record("b");
        top.record("b");
        assertEquals(List.of(new Ranked("b", 2), new Ranked("a", 1)), top.topK(5));
    }

    /** Expiry is per bucket: an event leaves between W-B and W after it happened. Here bucket 0 (second 5) is counted at second 59 and gone at second 60, when bucket 0 is exactly one window behind. */
    @Test
    void bucketExactlyOneWindowBehindDropsOut() {
        t.setSeconds(5);
        top.record("a");
        t.setSeconds(59);
        assertEquals(1, top.count("a"));
        t.setSeconds(60);
        assertEquals(0, top.count("a"));
    }

    /** Bucket edges are half-open: an event at second 60 belongs to bucket 6, not bucket 5. */
    @Test
    void bucketBoundaryBelongsToNextBucket() {
        assertEquals(5, SPEC.indexOf(sec(59)));
        assertEquals(6, SPEC.indexOf(sec(60)));
        assertTrue(SPEC.isLive(1, 6));
        assertFalse(SPEC.isLive(0, 6));
    }

    /** The classic bug: after an idle gap longer than the window the reused slot must be wiped, otherwise old counts come back. */
    @Test
    void staleBucketIsResetAfterIdleGapLongerThanWindow() {
        t.setSeconds(1);
        for (int i = 0; i < 5; i++) {
            top.record("old");
        }
        t.setSeconds(1 + 6 * 10 * 3);
        top.record("new");
        assertEquals(List.of(new Ranked("new", 1)), top.topK(5));
    }

    /** Same slot, gap of exactly one window: the old bucket must not leak into the new one. */
    @Test
    void sameSlotOneWindowLaterStartsClean() {
        t.setSeconds(3);
        top.record("old");
        t.setSeconds(63);
        top.record("new");
        assertEquals(0, top.count("old"));
        assertEquals(1, top.count("new"));
    }

    /** Late events inside the window go to their own bucket; older ones are rejected with false and counted nowhere. */
    @Test
    void lateEventRule() {
        t.setSeconds(100);
        assertTrue(top.recordAt("x", sec(55)));
        assertEquals(1, top.count("x"));
        assertFalse(top.recordAt("y", sec(45)));
        assertEquals(0, top.count("y"));
    }

    /** A timestamp in the future is a caller bug and throws, even one second ahead inside the current bucket, and also in the next bucket. */
    @Test
    void futureEventThrows() {
        t.setSeconds(100);
        assertThrows(IllegalArgumentException.class, () -> top.recordAt("a", sec(105)));
        assertThrows(IllegalArgumentException.class, () -> top.recordAt("a", sec(115)));
        assertThrows(IllegalArgumentException.class, () -> top.recordAt("a", sec(500)));
        assertEquals(0, top.count("a"));
    }

    /** Negative ticker readings (nanoTime may start anywhere) still land in the right buckets: floor division, not truncation. */
    @Test
    void negativeTickerReadingsWork() {
        FakeTicker neg = new FakeTicker(sec(-25));
        WindowedTopK w = new WindowedTopK(neg, SPEC);
        w.record("a");
        neg.setSeconds(-1);
        assertEquals(1, w.count("a"));
        neg.setSeconds(35);
        assertEquals(0, w.count("a"));
    }

    /** Window geometry with negative and edge numbers: floor division, floor modulo, and the upper edge of the live range. */
    @Test
    void specMathOnNegativesAndUpperEdge() {
        assertEquals(-3, SPEC.indexOf(sec(-25)));
        assertEquals(5, SPEC.slotOf(-1));
        assertFalse(SPEC.isLive(7, 6));
        assertTrue(SPEC.isLive(6, 6));
    }

    /** The lock is really held while the tracker reads its ticker: a probe ticker records Thread.holdsLock on every read. */
    @Test
    void lockIsHeldDuringOperations() {
        List<Boolean> held = new ArrayList<>();
        WindowedTopK[] box = new WindowedTopK[1];
        box[0] = new WindowedTopK(() -> {
            if (box[0] != null) {
                held.add(Thread.holdsLock(box[0]));
            }
            return 0L;
        }, SPEC);
        box[0].record("a");
        box[0].recordAt("a", 0L);
        box[0].topK(1);
        box[0].count("a");
        assertEquals(5, held.size());
        assertFalse(held.contains(false));
    }

    /** Blank ids and a negative k are rejected. */
    @Test
    void invalidInputs() {
        assertThrows(IllegalArgumentException.class, () -> top.record(" "));
        assertThrows(IllegalArgumentException.class, () -> top.record(null));
        assertThrows(IllegalArgumentException.class, () -> top.topK(-1));
    }

    /** k = 0, empty window and k bigger than the number of distinct items all give sensible answers. */
    @Test
    void edgeSizes() {
        assertEquals(List.of(), top.topK(3));
        top.record("a");
        top.record("b");
        assertEquals(List.of(), top.topK(0));
        assertEquals(2, top.topK(100).size());
        t.setSeconds(1000);
        assertEquals(List.of(), top.topK(3));
    }

    /** Equal counts are ordered by id so the answer never depends on hash order. */
    @Test
    void tiesBreakByIdAscending() {
        for (String s : new String[] {"c", "a", "b"}) {
            top.record(s);
        }
        assertEquals(List.of("a", "b"), top.topK(2).stream().map(Ranked::id).toList());
    }

    /** Window spec rejects a window that is not a whole number of buckets. */
    @Test
    void specValidation() {
        assertThrows(IllegalArgumentException.class, () -> WindowSpec.of(Duration.ofSeconds(7), Duration.ofSeconds(60)));
        assertThrows(IllegalArgumentException.class, () -> new WindowSpec(0, 3));
    }

    /** Random streams with random idle gaps (some longer than the window) give the same top-K as a brute-force list of timestamped events. */
    @Test
    void differentialAgainstBruteForce() {
        for (long seed = 1; seed <= 20; seed++) {
            Random rnd = new Random(seed);
            FakeTicker clock = new FakeTicker();
            WindowedTopK real = new WindowedTopK(clock, SPEC);
            List<Object[]> events = new ArrayList<>();
            for (int step = 0; step < 400; step++) {
                clock.advanceSeconds(rnd.nextInt(10) == 0 ? 20 + rnd.nextInt(150) : rnd.nextInt(6));
                String id = "i" + rnd.nextInt(12);
                real.record(id);
                events.add(new Object[] {id, clock.read()});
                if (step % 5 == 0) {
                    assertEquals(brute(events, clock.read(), 5), real.topK(5), "seed " + seed + " step " + step);
                }
            }
        }
    }

    /** The slow, obviously right model: keep every event, count those in the live bucket range. */
    private static List<Ranked> brute(List<Object[]> events, long now, int k) {
        long cur = SPEC.indexOf(now);
        Map<String, Long> m = new HashMap<>();
        for (Object[] e : events) {
            if (SPEC.isLive(SPEC.indexOf((Long) e[1]), cur)) {
                m.merge((String) e[0], 1L, Long::sum);
            }
        }
        return Ranked.top(m, k);
    }
}
