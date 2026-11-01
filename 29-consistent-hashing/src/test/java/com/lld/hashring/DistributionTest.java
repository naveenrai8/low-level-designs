package com.lld.hashring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Measures how evenly keys spread, and proves that more vnodes and weights behave as claimed. */
class DistributionTest {

    private static final int KEYS = 200_000;

    /** Counts keys per node for a locator. */
    private static Map<String, Integer> count(Locator l) {
        Map<String, Integer> c = new HashMap<>();
        for (int i = 0; i < KEYS; i++) {
            c.merge(l.locate("key-" + i), 1, Integer::sum);
        }
        return c;
    }

    /** Standard deviation of per-node counts divided by the mean (smaller is more even). */
    private static double cv(Map<String, Integer> c, int nodes) {
        double mean = KEYS / (double) nodes;
        double sq = 0;
        for (int i = 0; i < nodes; i++) {
            double d = c.getOrDefault("n" + i, 0) - mean;
            sq += d * d;
        }
        return Math.sqrt(sq / nodes) / mean;
    }

    /** Largest per-node count divided by the mean. */
    private static double maxOverMean(Map<String, Integer> c, int nodes) {
        int max = 0;
        for (int v : c.values()) {
            max = Math.max(max, v);
        }
        return max / (KEYS / (double) nodes);
    }

    /** Measures spread for 1, 10, 100 and 1000 vnodes per node (10 nodes), prints the table, and checks that spread shrinks as vnodes grow and ends under 10%. */
    @Test
    void moreVnodesCutTheSpread() {
        System.out.println("DIST keys=" + KEYS + " nodes=10 seed=5");
        System.out.println("DIST vnodes/node | std-dev/mean | max/mean");
        double previous = Double.MAX_VALUE;
        double last = 0;
        for (int vn : new int[] {1, 10, 100, 1000}) {
            ConsistentHashRing ring = new ConsistentHashRing(vn, 5);
            for (int i = 0; i < 10; i++) {
                ring.addNode("n" + i);
            }
            Map<String, Integer> c = count(ring);
            double cv = cv(c, 10);
            System.out.printf("DIST %d | %.4f | %.3f%n", vn, cv, maxOverMean(c, 10));
            assertTrue(cv < previous, "spread did not shrink at vnodes=" + vn);
            previous = cv;
            last = cv;
        }
        assertTrue(last < 0.10);
    }

    /** Rendezvous needs no vnodes and is already even: prints its row and checks std-dev/mean is under 2%. */
    @Test
    void rendezvousIsEvenWithoutVnodes() {
        RendezvousLocator l = new RendezvousLocator(5);
        for (int i = 0; i < 10; i++) {
            l.addNode("n" + i);
        }
        Map<String, Integer> c = count(l);
        System.out.printf("DIST rendezvous | %.4f | %.3f%n", cv(c, 10), maxOverMean(c, 10));
        assertTrue(cv(c, 10) < 0.02);
    }

    /** Nodes of weight 1, 2 and 5 receive key shares within 10% (relative) of 1/8, 2/8 and 5/8, for both implementations. */
    @ParameterizedTest
    @EnumSource(LocatorFactory.class)
    void shareFollowsWeight(LocatorFactory f) {
        Locator l = f.create(21);
        l.addNode("light", 1);
        l.addNode("mid", 2);
        l.addNode("heavy", 5);
        Map<String, Integer> c = count(l);
        double[] want = {1 / 8.0, 2 / 8.0, 5 / 8.0};
        String[] names = {"light", "mid", "heavy"};
        for (int i = 0; i < 3; i++) {
            double share = c.get(names[i]) / (double) KEYS;
            System.out.printf("WEIGHT %s %s share=%.4f want=%.4f%n", f, names[i], share, want[i]);
            assertEquals(want[i], share, want[i] * 0.10, f + " " + names[i]);
        }
    }
}
