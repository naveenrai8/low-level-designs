package com.lld.hashring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Proves ring lookup against a brute-force scan, plus wrap-around, exact-hit and collision rules. */
class RingLookupTest {

    /** A rigged hasher: texts found in the map get the listed position, anything else falls back to Murmur. */
    private static PointHasher rigged(Map<String, Long> fixed) {
        PointHasher real = PointHasher.murmur(0);
        return t -> fixed.containsKey(t) ? fixed.get(t) : real.hash(t);
    }

    /** The slow, obviously correct answer: scan every point, pick the smallest position at or after the key, else the smallest overall. Names are visited in sorted order and only a strictly smaller point replaces the best, so ties go to the smaller name. */
    private static String bruteForce(Map<String, Integer> members, int vnodesPerWeight, PointHasher h, String key) {
        long kh = h.hash(key);
        long bestAfter = 0;
        String ownerAfter = null;
        long bestAny = 0;
        String ownerAny = null;
        for (Map.Entry<String, Integer> e : new TreeMap<>(members).entrySet()) {
            for (int i = 0; i < e.getValue() * vnodesPerWeight; i++) {
                long p = h.hash(ConsistentHashRing.vnodeLabel(e.getKey(), i));
                if (ownerAny == null || Long.compare(p, bestAny) < 0) {
                    bestAny = p;
                    ownerAny = e.getKey();
                }
                if (Long.compare(p, kh) >= 0 && (ownerAfter == null || Long.compare(p, bestAfter) < 0)) {
                    bestAfter = p;
                    ownerAfter = e.getKey();
                }
            }
        }
        return ownerAfter != null ? ownerAfter : ownerAny;
    }

    /** Over 60 seeded ring shapes (1 to 12 nodes, mixed weights, 1 to 8 vnodes) and 300 random keys each, the binary search equals the brute-force scan. */
    @Test
    void differentialAgainstBruteForce() {
        Random rnd = new Random(42);
        for (int shape = 0; shape < 60; shape++) {
            int vn = 1 + rnd.nextInt(8);
            PointHasher h = PointHasher.murmur(rnd.nextInt(1000));
            ConsistentHashRing ring = new ConsistentHashRing(vn, h);
            Map<String, Integer> members = new TreeMap<>();
            int nodes = 1 + rnd.nextInt(12);
            for (int n = 0; n < nodes; n++) {
                int w = 1 + rnd.nextInt(3);
                members.put("n" + n, w);
                ring.addNode("n" + n, w);
            }
            for (int k = 0; k < 300; k++) {
                String key = "key-" + rnd.nextLong();
                assertEquals(bruteForce(members, vn, h, key), ring.locate(key), "shape " + shape + " " + key);
            }
        }
    }

    /** A key hashed past the last point wraps to the first point; a key before the first point also lands on the first. */
    @Test
    void wrapAroundGoesToFirstPoint() {
        ConsistentHashRing ring = new ConsistentHashRing(1, rigged(Map.of("a#0", 100L, "b#0", 200L, "past", 201L, "huge", Long.MAX_VALUE, "tiny", Long.MIN_VALUE)));
        ring.addNode("a");
        ring.addNode("b");
        assertEquals("a", ring.locate("past"));
        assertEquals("a", ring.locate("huge"));
        assertEquals("a", ring.locate("tiny"));
    }

    /** A key that hashes exactly onto a point belongs to that point (at or after, not strictly after); one more goes to the next point. */
    @Test
    void exactPointHitBelongsToThatPoint() {
        ConsistentHashRing ring = new ConsistentHashRing(1, rigged(Map.of("a#0", 100L, "b#0", 200L, "hitA", 100L, "hitB", 200L, "afterA", 101L, "beforeA", 99L)));
        ring.addNode("a");
        ring.addNode("b");
        assertEquals("a", ring.locate("hitA"));
        assertEquals("b", ring.locate("hitB"));
        assertEquals("b", ring.locate("afterA"));
        assertEquals("a", ring.locate("beforeA"));
    }

    /** Two vnodes at the same position: the lexically smaller node name wins, whatever the insertion order, so every machine agrees. */
    @Test
    void collidingPointsBreakTieByNodeName() {
        Map<String, Long> fixed = Map.of("zed#0", 500L, "amy#0", 500L, "k", 400L);
        ConsistentHashRing one = new ConsistentHashRing(1, rigged(fixed));
        one.addNode("zed");
        one.addNode("amy");
        ConsistentHashRing two = new ConsistentHashRing(1, rigged(fixed));
        two.addNode("amy");
        two.addNode("zed");
        assertEquals("amy", one.locate("k"));
        assertEquals("amy", two.locate("k"));
    }

    /** Removing the tie winner hands its position to the other node and disturbs no other key: over 500 keys, every key the winner did not own keeps its owner, and every key it owned moves. */
    @Test
    void collisionLoserTakesOverWhenWinnerLeaves() {
        ConsistentHashRing ring = new ConsistentHashRing(5, rigged(Map.of("zed#0", 500L, "amy#0", 500L, "k", 400L)));
        ring.addNode("zed");
        ring.addNode("amy");
        ring.addNode("bob");
        String[] before = new String[500];
        for (int i = 0; i < 500; i++) {
            before[i] = ring.locate(i == 0 ? "k" : "key-" + i);
        }
        assertEquals("amy", before[0]);
        ring.removeNode("amy");
        for (int i = 0; i < 500; i++) {
            String after = ring.locate(i == 0 ? "k" : "key-" + i);
            if (before[i].equals("amy")) {
                assertTrue(!after.equals("amy"));
            } else {
                assertEquals(before[i], after, "key " + i);
            }
        }
        assertEquals("zed", ring.locate("k"));
    }

    /** The ring constructors reject a vnode count below 1 and a missing hasher. */
    @Test
    void constructorContract() {
        assertThrows(IllegalArgumentException.class, () -> new ConsistentHashRing(0, 1));
        assertThrows(NullPointerException.class, () -> new ConsistentHashRing(1, (PointHasher) null));
    }

    /** The tie-break must come from the comparator, not from the order the members happen to be iterated: handing build a map sorted in reverse still makes the smaller name win a position tie. */
    @Test
    void tieBreakIgnoresMapIterationOrder() {
        ConsistentHashRing ring = new ConsistentHashRing(1, rigged(Map.of("zed#0", 500L, "amy#0", 500L, "k", 400L)));
        TreeMap<String, Integer> reversed = new TreeMap<>(java.util.Comparator.reverseOrder());
        reversed.put("amy", 1);
        reversed.put("zed", 1);
        assertEquals("amy", ring.build(reversed).locate("k"));
    }

    /** The preference list is the next distinct nodes clockwise: with vnodes of the same node sitting side by side the list still has no repeats. */
    @Test
    void replicationListIsDistinctEvenWithAdjacentVnodes() {
        // a owns three adjacent points (10, 11, 12) before b (20..22) and c (30..32).
        ConsistentHashRing ring = new ConsistentHashRing(3, rigged(Map.ofEntries(
                Map.entry("a#0", 10L), Map.entry("a#1", 11L), Map.entry("a#2", 12L),
                Map.entry("b#0", 20L), Map.entry("b#1", 21L), Map.entry("b#2", 22L),
                Map.entry("c#0", 30L), Map.entry("c#1", 31L), Map.entry("c#2", 32L), Map.entry("k", 5L))));
        ring.addNode("a");
        ring.addNode("b");
        ring.addNode("c");
        assertEquals(List.of("a", "b", "c"), ring.locate("k", 3));
        assertEquals(List.of("a", "b"), ring.locate("k", 2));
        assertEquals(List.of("a", "b", "c"), ring.locate("k", 10));
    }

    /** For many random keys the first entry of the preference list equals the single-owner answer and all entries are distinct. */
    @Test
    void preferenceListAgreesWithLocate() {
        ConsistentHashRing ring = new ConsistentHashRing(50, 3);
        for (int i = 0; i < 6; i++) {
            ring.addNode("n" + i);
        }
        Random rnd = new Random(9);
        for (int i = 0; i < 500; i++) {
            String key = "k" + rnd.nextInt();
            List<String> l = ring.locate(key, 3);
            assertEquals(ring.locate(key), l.get(0));
            assertEquals(3, new HashSet<>(l).size());
        }
    }
}
