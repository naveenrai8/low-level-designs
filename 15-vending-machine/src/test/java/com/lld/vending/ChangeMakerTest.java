package com.lld.vending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Proves change-making is exact, optimal, and respects limited coin stock, including against a brute-force reference. */
class ChangeMakerTest {

    /** The textbook greedy: biggest coin first, as many as fit and are in stock. Used only to show where it fails. */
    private static Optional<Map<Long, Integer>> greedy(long amount, Map<Long, Integer> stock) {
        Map<Long, Integer> out = new TreeMap<>();
        long left = amount;
        for (Map.Entry<Long, Integer> e : new TreeMap<>(stock).descendingMap().entrySet()) {
            long take = Math.min(e.getValue(), left / e.getKey());
            if (take > 0) {
                out.put(e.getKey(), (int) take);
                left -= take * e.getKey();
            }
        }
        return left == 0 ? Optional.of(out) : Optional.empty();
    }

    /** Counts coins in a result. */
    private static int coinCount(Map<Long, Integer> m) {
        return m.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Coins {1,3,4}, change 6: greedy gives 4+1+1 (3 coins) but the best is 3+3 (2 coins). */
    @Test
    void nonCanonicalCoinSetBeatsGreedy() {
        Map<Long, Integer> stock = Map.of(1L, 10, 3L, 10, 4L, 10);
        assertEquals(3, coinCount(greedy(6, stock).orElseThrow()));
        Map<Long, Integer> best = ChangeMaker.make(6, stock).orElseThrow();
        assertEquals(Map.of(3L, 2), best);
    }

    /** With one 5 and three 2s, change 6: greedy takes the 5, strands 1 and finds nothing, yet 2+2+2 exists. */
    @Test
    void greedyFindsNothingButAnAnswerExists() {
        Map<Long, Integer> stock = Map.of(5L, 1, 2L, 3);
        assertTrue(greedy(6, stock).isEmpty());
        assertEquals(Map.of(2L, 3), ChangeMaker.make(6, stock).orElseThrow());
    }

    /** Limited stock is respected: asking for 6 with only two 3s works, with only one 3 and one 1 it does not. */
    @Test
    void neverUsesMoreCoinsThanStocked() {
        assertEquals(Map.of(3L, 2), ChangeMaker.make(6, Map.of(3L, 2)).orElseThrow());
        assertTrue(ChangeMaker.make(6, Map.of(3L, 1, 1L, 1)).isEmpty());
    }

    /** Zero change needs no coins, even with an empty box. */
    @Test
    void zeroAmountIsEmptyAnswer() {
        assertEquals(Map.of(), ChangeMaker.make(0, Map.of()).orElseThrow());
    }

    /** An amount that cannot be made exactly gives no answer rather than a wrong one. */
    @Test
    void impossibleAmountGivesEmpty() {
        assertTrue(ChangeMaker.make(7, Map.of(5L, 5, 10L, 5)).isEmpty());
    }

    /** The slow, obviously-correct reference: tries every count of every coin and returns the fewest coins, or -1. */
    private static int bruteForce(long[] values, int[] counts, int index, long left) {
        if (left == 0) {
            return 0;
        }
        if (index == values.length) {
            return -1;
        }
        int best = -1;
        for (int k = 0; k <= counts[index] && k * values[index] <= left; k++) {
            int rest = bruteForce(values, counts, index + 1, left - k * values[index]);
            if (rest >= 0 && (best < 0 || rest + k < best)) {
                best = rest + k;
            }
        }
        return best;
    }

    /** 3000 random small stocks: the DP agrees with brute force on solvable-or-not and on the fewest coins, and its answer really sums to the amount within stock. */
    @Test
    void differentialAgainstBruteForce() {
        Random rnd = new Random(42);
        for (int round = 0; round < 3000; round++) {
            int kinds = 1 + rnd.nextInt(4);
            long[] values = new long[kinds];
            int[] counts = new int[kinds];
            Map<Long, Integer> stock = new TreeMap<>();
            for (int i = 0; i < kinds; i++) {
                values[i] = 1 + rnd.nextInt(9);
                counts[i] = rnd.nextInt(4);
                stock.merge(values[i], counts[i], Integer::sum);
            }
            // merge duplicates so brute force sees the same stock
            long[] v = stock.keySet().stream().mapToLong(Long::longValue).toArray();
            int[] c = stock.values().stream().mapToInt(Integer::intValue).toArray();
            long amount = rnd.nextInt(31);
            int expected = bruteForce(v, c, 0, amount);
            Optional<Map<Long, Integer>> got = ChangeMaker.make(amount, stock);
            assertEquals(expected >= 0, got.isPresent(), "stock " + stock + " amount " + amount);
            if (got.isPresent()) {
                assertEquals(expected, coinCount(got.get()), "stock " + stock + " amount " + amount);
                assertEquals(amount, new Payout(got.get()).total());
                got.get().forEach((coin, n) -> assertTrue(n <= stock.get(coin)));
            }
        }
    }
}
