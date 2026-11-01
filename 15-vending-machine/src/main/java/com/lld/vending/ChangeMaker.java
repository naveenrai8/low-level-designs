package com.lld.vending;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Works out which coins to hand back as change, using only the coins actually in the machine.
 *
 * <p>Why not greedy ("take the biggest coin that fits")? It fails on coin sets like {1, 3, 4}: for 6 it gives 4+1+1 (three coins) while 3+3 needs two. With limited stock it can even fail to find any answer: with one 5 and three 2s, greedy takes the 5, is left with 1, and gives up, while 2+2+2 works.
 *
 * <p>So this uses dynamic programming (DP): for each amount from 0 to the target it remembers the fewest coins that make it, one denomination at a time, never using more of a coin than the machine holds. Cost is O(amount x total coins) time and O(denominations x amount) memory, which is tiny for vending amounts.
 */
public final class ChangeMaker {

    private static final int NONE = Integer.MAX_VALUE;
    /** A guard so a typo like a billion cannot allocate a huge table: about 4 MB per denomination at this limit (estimate: one int per cent). Vending amounts are far below this. */
    private static final long MAX_AMOUNT = 1_000_000L;

    private ChangeMaker() {}

    /**
     * Finds the fewest coins that add up exactly to {@code amount}, using at most the given stock of each coin.
     *
     * <p>Worked example: stock {2: three coins, 5: one coin}, amount 6. Layer for coin 2 alone: 0 needs 0 coins, 2 needs 1, 4 needs 2, 6 needs 3. Adding coin 5 (at most one): 5 needs 1, 7 needs 2, and 6 stays at three 2s because 5+1 has no 1. Result: 2+2+2.
     *
     * @param amount    what to make, in minor units; 0 gives an empty answer
     * @param available coin value to how many the machine holds; zero or negative counts are ignored
     * @return coin value to count with the fewest coins, or empty when no exact answer exists
     */
    public static Optional<Map<Long, Integer>> make(long amount, Map<Long, Integer> available) {
        if (amount < 0 || amount > MAX_AMOUNT) {
            throw new IllegalArgumentException("amount out of range: " + amount);
        }
        SortedMap<Long, Integer> stock = new TreeMap<>();
        available.forEach((value, count) -> {
            if (value <= 0) {
                throw new IllegalArgumentException("coin value must be above zero: " + value);
            }
            if (count > 0) {
                stock.put(value, count);
            }
        });
        int target = (int) amount;
        long[] values = stock.keySet().stream().mapToLong(Long::longValue).toArray();
        int n = values.length;
        int[] best = new int[target + 1];
        Arrays.fill(best, NONE);
        best[0] = 0;
        int[][] used = new int[n][target + 1];
        for (int i = 0; i < n; i++) {
            int coin = (int) Math.min(values[i], MAX_AMOUNT + 1);
            int limit = stock.get(values[i]);
            int[] next = new int[target + 1];
            Arrays.fill(next, NONE);
            for (int a = 0; a <= target; a++) {
                for (int k = 0; k <= limit && (long) k * coin <= a; k++) {
                    int before = best[a - k * coin];
                    if (before != NONE && before + k < next[a]) {
                        next[a] = before + k;
                        used[i][a] = k;
                    }
                }
            }
            best = next;
        }
        if (best[target] == NONE) {
            return Optional.empty();
        }
        Map<Long, Integer> answer = new TreeMap<>();
        int left = target;
        for (int i = n - 1; i >= 0; i--) {
            int k = used[i][left];
            if (k > 0) {
                answer.put(values[i], k);
                left -= k * (int) values[i];
            }
        }
        return Optional.of(answer);
    }
}
