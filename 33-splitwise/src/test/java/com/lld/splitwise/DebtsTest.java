package com.lld.splitwise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Proves debt simplification: greedy settles exactly with at most n-1 transfers, is not always optimal, and the DP matches brute force. */
class DebtsTest {

    /** The counterexample: -9, -8, 2, 7, 8 splits into {-9, 2, 7} and {-8, 8}, so 3 transfers are enough, but greedy needs 4. */
    private static final Map<String, Long> COUNTER = Map.of("a", -9L, "b", -8L, "c", 2L, "d", 7L, "e", 8L);

    /** Applies the transfers to the nets and checks every net becomes exactly 0. */
    private static void assertSettles(Map<String, Long> nets, List<Transfer> transfers) {
        Map<String, Long> left = new TreeMap<>(nets);
        for (Transfer t : transfers) {
            left.merge(t.from(), t.amount(), Long::sum);
            left.merge(t.to(), -t.amount(), Long::sum);
        }
        left.values().forEach(v -> assertEquals(0L, v));
    }

    /** Counts people with a non-zero net. */
    private static int nonZero(Map<String, Long> nets) {
        return (int) nets.values().stream().filter(v -> v != 0).count();
    }

    /** Three people where two debtors each owe one creditor: greedy settles with two transfers, biggest debtor first (tie: smaller id). */
    @Test
    void worksTheReadmeExample() {
        Map<String, Long> nets = Map.of("alice", 4000L, "bob", -2000L, "carol", -2000L);
        List<Transfer> t = Debts.greedy(nets);
        assertEquals(List.of(new Transfer("bob", "alice", 2000), new Transfer("carol", "alice", 2000)), t);
    }

    /** Greedy on the counterexample settles everyone but uses 4 transfers while the optimum is 3. */
    @Test
    void greedyIsNotOptimalOnCounterexample() {
        List<Transfer> g = Debts.greedy(COUNTER);
        assertSettles(COUNTER, g);
        assertEquals(4, g.size());
        assertEquals(3, Debts.optimalCount(COUNTER));
    }

    /** Nets that do not add up to 0 are rejected, by both algorithms. */
    @Test
    void rejectsNonZeroSum() {
        assertThrows(IllegalArgumentException.class, () -> Debts.greedy(Map.of("a", 1L)));
        assertThrows(IllegalArgumentException.class, () -> Debts.optimalCount(Map.of("a", 1L)));
    }

    /** Builds 20 people with +1 and -1 (the exact solver's limit), or 21 people with twenty +1 and one -20. */
    private static Map<String, Long> people(boolean overLimit) {
        Map<String, Long> nets = new TreeMap<>();
        if (overLimit) {
            for (int i = 0; i < Debts.MAX_EXACT; i++) {
                nets.put("u" + i, 1L);
            }
            nets.put("z", -(long) Debts.MAX_EXACT);
        } else {
            for (int i = 0; i < Debts.MAX_EXACT; i++) {
                nets.put("u" + i, i % 2 == 0 ? 1L : -1L);
            }
        }
        return nets;
    }

    /** Exactly MAX_EXACT non-zero people are accepted (ten pairs, so 10 transfers), and one more is refused, not left to run out of memory. */
    @Test
    void exactSolverBoundaryIsExactlyMaxExact() {
        assertEquals(10, Debts.optimalCount(people(false)));
        assertEquals(Debts.MAX_EXACT + 1, people(true).size());
        assertThrows(IllegalArgumentException.class, () -> Debts.optimalCount(people(true)));
    }

    /** Subset sums use exact arithmetic: two +MAX and two -MAX nets add up to 0 overall, but a subset of the two +MAX overflows and must throw, not wrap. */
    @Test
    void exactSolverSubsetSumsDoNotWrap() {
        Map<String, Long> nets = Map.of("a", Long.MAX_VALUE, "b", -Long.MAX_VALUE, "c", Long.MAX_VALUE, "d", -Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> Debts.optimalCount(nets));
    }

    /** An all-zero or empty map needs no transfers. */
    @Test
    void nothingToSettle() {
        assertEquals(0, Debts.greedy(Map.of()).size());
        assertEquals(0, Debts.greedy(Map.of("a", 0L)).size());
        assertEquals(0, Debts.optimalCount(Map.of("a", 0L)));
    }

    /** Random nets (seeded): greedy settles exactly, uses at most nonZero-1 transfers, and never beats the optimum. */
    @Test
    void greedySettlesWithAtMostNMinusOneAndNeverBeatsOptimal() {
        Random rnd = new Random(7);
        int worse = 0;
        for (int round = 0; round < 3000; round++) {
            Map<String, Long> nets = randomNets(rnd, 2 + rnd.nextInt(9), 20);
            List<Transfer> g = Debts.greedy(nets);
            assertSettles(nets, g);
            int nz = nonZero(nets);
            assertTrue(g.size() <= Math.max(0, nz - 1), "too many transfers");
            int opt = Debts.optimalCount(nets);
            assertTrue(g.size() >= opt, "greedy beat optimal?");
            if (g.size() > opt) {
                worse++;
            }
        }
        assertTrue(worse > 0, "random search should find at least one non-optimal greedy case");
    }

    /** Differential test: the bitmask DP agrees with a plain recursive brute force over all set partitions for up to 8 people. */
    @Test
    void dpMatchesBruteForce() {
        Random rnd = new Random(99);
        for (int round = 0; round < 400; round++) {
            Map<String, Long> nets = randomNets(rnd, 1 + rnd.nextInt(8), 6);
            long[] v = Debts.nonZero(nets);
            assertEquals(v.length - brute(v, 0, new java.util.ArrayList<>()), Debts.optimalCount(nets));
        }
    }

    /** Brute force: puts each person in an existing group or a new one, and returns the most groups that all sum to 0. */
    private static int brute(long[] v, int i, List<long[]> groups) {
        if (i == v.length) {
            return groups.stream().allMatch(g -> g[0] == 0) ? groups.size() : 0;
        }
        int best = 0;
        int open = groups.size();
        for (int k = 0; k < open; k++) {
            groups.get(k)[0] += v[i];
            best = Math.max(best, brute(v, i + 1, groups));
            groups.get(k)[0] -= v[i];
        }
        groups.add(new long[] {v[i]});
        best = Math.max(best, brute(v, i + 1, groups));
        groups.remove(groups.size() - 1);
        return best;
    }

    /** Makes n random nets in -max..max whose last entry fixes the total to 0. */
    private static Map<String, Long> randomNets(Random rnd, int n, int max) {
        Map<String, Long> nets = new TreeMap<>();
        long sum = 0;
        for (int i = 0; i < n - 1; i++) {
            long v = rnd.nextInt(2 * max + 1) - max;
            nets.put("u" + i, v);
            sum += v;
        }
        nets.put("u" + (n - 1), -sum);
        return nets;
    }
}
