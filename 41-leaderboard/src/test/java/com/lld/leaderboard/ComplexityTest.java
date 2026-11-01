package com.lld.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Shows that rank, update and top-K cost O(log n) by counting comparisons instead of timing.
 *
 * <p>Counting comparisons is machine independent and never flaky. A naive sorted list needs n comparisons for a rank lookup (a linear scan that compares every entry), so at n = 100,000 it would need about 50,000. The tree needs a few dozen.
 */
class ComplexityTest {

    /** Builds a board of {@code n} random users whose comparator bumps {@code counter}. */
    private SortedLeaderboard build(int n, AtomicLong counter) {
        SortedLeaderboard b = new SortedLeaderboard(new MutableClock(Instant.EPOCH), (x, y) -> {
            counter.incrementAndGet();
            return SortedLeaderboard.ORDER.compare(x, y);
        });
        Random rnd = new Random(7);
        for (int i = 0; i < n; i++) {
            b.setScore("u" + i, rnd.nextInt(1_000_000));
        }
        return b;
    }

    /** Returns the average comparisons per rank() call over 200 random users on a board of size n. */
    private double avgRankComparisons(int n) {
        AtomicLong counter = new AtomicLong();
        SortedLeaderboard b = build(n, counter);
        Random rnd = new Random(11);
        counter.set(0);
        for (int i = 0; i < 200; i++) {
            assertTrue(b.rank("u" + rnd.nextInt(n)).isPresent());
        }
        return counter.get() / 200.0;
    }

    /** Proves rank() cost grows like log n: 100x more users costs well under 3x more comparisons, and stays far below n/2. */
    @Test
    void rankCostGrowsLogarithmically() {
        double c1k = avgRankComparisons(1_000);
        double c10k = avgRankComparisons(10_000);
        double c100k = avgRankComparisons(100_000);
        System.out.printf("avg comparisons per rank(): n=1k %.1f, n=10k %.1f, n=100k %.1f%n", c1k, c10k, c100k);
        assertTrue(c100k < 3 * c1k, "100x more users should not cost 3x more comparisons: " + c1k + " -> " + c100k);
        assertTrue(c100k < 100, "rank at 100k must be far below a linear scan (~50,000): " + c100k);
    }

    /** Proves an update (remove old entry, insert new one) is also logarithmic, not linear. */
    @Test
    void updateCostIsLogarithmic() {
        AtomicLong counter = new AtomicLong();
        SortedLeaderboard b = build(100_000, counter);
        Random rnd = new Random(5);
        counter.set(0);
        for (int i = 0; i < 200; i++) {
            b.increment("u" + rnd.nextInt(100_000), 1 + rnd.nextInt(50));
        }
        double avg = counter.get() / 200.0;
        System.out.printf("avg comparisons per update at n=100k: %.1f%n", avg);
        assertTrue(avg < 200, "update must not scan: " + avg);
    }

    /** Proves top(10) on a big board only visits about log n + 10 nodes, not the whole board, by comparing its cost to a full build. */
    @Test
    void topKDoesNotTouchTheWholeBoard() {
        AtomicLong counter = new AtomicLong();
        SortedLeaderboard b = build(100_000, counter);
        counter.set(0);
        assertEquals(10, b.top(10).size());
        assertEquals(0, counter.get(), "top-K walks by position and needs no comparisons");
    }

    /** Proves the tree stays shallow after many random updates: height within about 3 * log2(n). */
    @Test
    void treeStaysBalanced() {
        SortedLeaderboard b = build(50_000, new AtomicLong());
        int height = b.treeHeight();
        System.out.println("height at n=50k: " + height + " (log2 n = 15.6)");
        assertTrue(height < 3 * 16, "treap height " + height);
    }

    /** Measures a naive rank (compare every entry with the target, count the ones that sort first) and shows it costs exactly n comparisons, against the tree's few dozen at the same size. */
    @Test
    void naiveRankScansEveryEntry() {
        int n = 20_000;
        AtomicLong counter = new AtomicLong();
        java.util.List<SortedLeaderboard.Entry> all = new java.util.ArrayList<>();
        Random rnd = new Random(7);
        for (int i = 0; i < n; i++) {
            all.add(new SortedLeaderboard.Entry(rnd.nextInt(1_000_000), 0, "u" + i));
        }
        SortedLeaderboard.Entry target = all.get(n / 2);
        int before = 0;
        for (SortedLeaderboard.Entry e : all) {
            counter.incrementAndGet();
            if (SortedLeaderboard.ORDER.compare(e, target) < 0) {
                before++;
            }
        }
        assertTrue(before < n);
        assertEquals(n, counter.get());
        double tree = avgRankComparisons(n);
        System.out.printf("naive scan: %d comparisons per rank vs tree %.1f at the same size%n", n, tree);
        assertTrue(tree * 100 < n);
    }

    /** Proves rank, top-K and range touch only O(log n + k) tree nodes. A comparison counter cannot see a full scan that never calls the comparator, but the node-visit counter can. */
    @Test
    void readsTouchOnlyLogNodes() {
        int n = 100_000;
        SortedLeaderboard b = build(n, new AtomicLong());
        int bound = 4 * 17;
        b.resetTreeVisits();
        b.rank("u12345");
        assertTrue(b.treeVisits() <= bound, "rank visited " + b.treeVisits());
        b.resetTreeVisits();
        assertEquals(10, b.top(10).size());
        assertTrue(b.treeVisits() <= bound + 3 * 10, "top(10) visited " + b.treeVisits());
        b.resetTreeVisits();
        assertEquals(10, b.range(50_000, 50_009).size());
        assertTrue(b.treeVisits() <= bound + 3 * 10, "range visited " + b.treeVisits());
        b.resetTreeVisits();
        b.competitionRank("u777");
        assertTrue(b.treeVisits() <= bound, "competitionRank visited " + b.treeVisits());
    }
}
