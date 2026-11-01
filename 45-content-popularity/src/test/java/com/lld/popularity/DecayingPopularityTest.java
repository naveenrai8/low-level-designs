package com.lld.popularity;

import static com.lld.popularity.FakeTicker.sec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Tests exponential decay: the half-life table, the lazy-scaling trick, overflow handling and a differential check against a naive recompute. */
class DecayingPopularityTest {

    private static final long HALF = sec(100);

    private final FakeTicker t = new FakeTicker();
    private final DecayingPopularity d = new DecayingPopularity(t, HALF);

    /** One event of weight 1 reads 1, 0.5, 0.25, 0.125 after 0, 1, 2, 3 half-lives. */
    @Test
    void halvesEveryHalfLife() {
        d.record("a");
        assertEquals(1.0, d.score("a"), 1e-12);
        t.setSeconds(100);
        assertEquals(0.5, d.score("a"), 1e-12);
        t.setSeconds(200);
        assertEquals(0.25, d.score("a"), 1e-12);
        t.setSeconds(300);
        assertEquals(0.125, d.score("a"), 1e-12);
    }

    /** A newer event can outrank more old ones: two events 3 half-lives old (0.25) lose to one fresh event (1). */
    @Test
    void recentBeatsOld() {
        d.record("old");
        d.record("old");
        t.setSeconds(300);
        d.record("fresh");
        assertEquals("fresh", d.topK(1).get(0).id());
        assertEquals(0.25, d.score("old"), 1e-12);
    }

    /** Running far past the rescale point (10000 half-lives with events all along) never overflows: scores stay finite and correct. */
    @Test
    void longRunDoesNotOverflow() {
        for (int h = 0; h < 10_000; h++) {
            t.advanceSeconds(100);
            d.record("steady");
        }
        double s = d.score("steady");
        assertTrue(Double.isFinite(s));
        assertEquals(2.0, s, 1e-9);
    }

    /** After an idle gap of a million half-lives the old item is exactly gone (no NaN, no infinity) and new events still work. */
    @Test
    void hugeIdleGapUnderflowsToZero() {
        d.record("a");
        t.setSeconds(100L * 1_000_000);
        assertEquals(0.0, d.score("a"));
        d.record("b");
        assertEquals(1, d.size());
        assertEquals(1.0, d.score("b"), 1e-12);
        assertFalse(Double.isNaN(d.score("a")));
    }

    /** Random events at random times: the lazy version ranks and scores like a naive sum of weight * 0.5^(age/halfLife) within 1e-9 relative. */
    @Test
    void differentialAgainstNaiveRecompute() {
        for (long seed = 1; seed <= 10; seed++) {
            Random rnd = new Random(seed);
            FakeTicker c = new FakeTicker();
            DecayingPopularity lazy = new DecayingPopularity(c, HALF);
            List<Object[]> events = new ArrayList<>();
            for (int i = 0; i < 600; i++) {
                c.advanceSeconds(rnd.nextInt(20) == 0 ? 3000 + rnd.nextInt(30_000) : rnd.nextInt(80));
                String id = "i" + rnd.nextInt(8);
                double w = 1 + rnd.nextInt(3);
                lazy.record(id, w);
                events.add(new Object[] {id, c.read(), w});
            }
            for (int k = 0; k < 8; k++) {
                String id = "i" + k;
                double naive = 0;
                for (Object[] e : events) {
                    if (e[0].equals(id)) {
                        naive += (Double) e[2] * Math.pow(0.5, (c.read() - (Long) e[1]) / (double) HALF);
                    }
                }
                assertEquals(naive, lazy.score(id), Math.max(1e-9, naive * 1e-9), "seed " + seed + " " + id);
            }
        }
    }

    /** Bad half-life, weight and k are rejected. */
    @Test
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> new DecayingPopularity(t, 0));
        assertThrows(IllegalArgumentException.class, () -> d.record("a", 0));
        assertThrows(IllegalArgumentException.class, () -> d.record("a", Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> d.record("a", 1e-7));
        assertThrows(IllegalArgumentException.class, () -> d.record("a", 1e251));
        assertThrows(IllegalArgumentException.class, () -> d.topK(-1));
        assertEquals(List.of(), d.topK(0));
    }

    /** Equal scores are ordered by id. */
    @Test
    void decayedTiesBreakById() {
        d.record("b");
        d.record("a");
        assertEquals("a", d.topK(2).get(0).id());
    }

    /** A tiny but non-zero score is pruned at the next rebase (bounding memory), not kept forever. */
    @Test
    void tinyScoresArePruned() {
        d.record("a");
        t.setSeconds(100 * 50);
        d.record("b");
        t.setSeconds(100 * 120);
        d.record("b");
        assertEquals(1, d.size());
        assertEquals(1.0, d.score("b"), 1e-12);
    }

    /** The smallest allowed weight survives while fresh, and the largest allowed weight stays finite after a long run. */
    @Test
    void weightRangeEnds() {
        t.setSeconds(100 * 100);
        d.record("small", DecayingPopularity.MIN_WEIGHT);
        assertEquals(1e-6, d.score("small"), 1e-18);
        d.record("big", DecayingPopularity.MAX_WEIGHT);
        assertEquals(1e250, d.score("big"), 1e240);
        assertEquals(2, d.size());
    }

    /** Rebase must happen before the growth factor overflows: 900 half-lives with weight 1e50 would be 2^900 * 1e50 = infinity without it. */
    @Test
    void rebaseHappensBeforeOverflow() {
        d.record("a");
        t.setSeconds(100 * 900);
        d.record("a", 1e50);
        assertTrue(Double.isFinite(d.score("a")));
        assertEquals(1e50, d.score("a"), 1e40);
    }

    /** The lock is really held while the tracker reads its ticker. */
    @Test
    void lockIsHeldDuringOperations() {
        List<Boolean> held = new ArrayList<>();
        DecayingPopularity[] box = new DecayingPopularity[1];
        box[0] = new DecayingPopularity(() -> {
            if (box[0] != null) {
                held.add(Thread.holdsLock(box[0]));
            }
            return 0L;
        }, HALF);
        box[0].record("a");
        box[0].score("a");
        box[0].topK(1);
        assertEquals(3, held.size());
        assertFalse(held.contains(false));
    }
}
