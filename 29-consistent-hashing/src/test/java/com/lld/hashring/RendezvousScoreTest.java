package com.lld.hashring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Proves the rendezvous score is always finite and positive, and that a key named like a node can still land on that node. */
class RendezvousScoreTest {

    /** For the extreme mixed values 0 and -1 (all ones), and some values between, the score is finite and positive. */
    @Test
    void scoreIsFiniteAndPositiveAtTheExtremes() {
        for (long mixed : new long[] {0L, 1L, 1L << 62, Long.MIN_VALUE, -2L, -1L}) {
            double s = RendezvousLocator.score(mixed, 1);
            assertTrue(Double.isFinite(s) && s > 0, "mixed=" + mixed + " score=" + s);
        }
    }

    /** Treated as unsigned, a bigger mixed value gives a strictly bigger score, so 0 is the lowest and -1 the highest. */
    @Test
    void scoreIsOrderedByMixedValue() {
        double lo = RendezvousLocator.score(0L, 1);
        double mid = RendezvousLocator.score(1L << 62, 1);
        double hi = RendezvousLocator.score(-1L, 1);
        assertTrue(lo < mid && mid < hi, lo + " " + mid + " " + hi);
    }

    /** At the same mixed value a heavier node scores exactly proportionally higher. */
    @Test
    void scoreScalesWithWeight() {
        assertEquals(3 * RendezvousLocator.score(12345L, 1), RendezvousLocator.score(12345L, 3), 1e-9);
    }

    /** With the same hasher for keys and node names, a key equal to a node's name used to give XOR 0, the lowest score, so it could never pick that node. Over 100 seeds some such keys must now map to their namesake. */
    @Test
    void keyEqualToNodeNameCanMapToThatNode() {
        int self = 0;
        for (long seed = 0; seed < 100; seed++) {
            RendezvousLocator l = new RendezvousLocator(seed);
            for (int i = 0; i < 4; i++) {
                l.addNode("n" + i);
            }
            for (int i = 0; i < 4; i++) {
                if (l.locate("n" + i).equals("n" + i)) {
                    self++;
                }
            }
        }
        assertTrue(self > 0, "no key ever mapped to the node with its own name");
    }
}
