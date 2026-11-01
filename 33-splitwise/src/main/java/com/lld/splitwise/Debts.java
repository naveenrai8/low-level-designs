package com.lld.splitwise;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;

/**
 * Debt simplification: turn "net per person" into a short list of payments that settles everybody.
 */
public final class Debts {

    /** Largest number of non-zero people the exact solver accepts (2^20 subsets is about a million, still instant). */
    public static final int MAX_EXACT = 20;

    /** Not instantiable: only static helpers. */
    private Debts() {
    }

    /**
     * Greedy min-cash-flow: repeatedly pay the largest debtor's debt to the largest creditor, for as much as both can take.
     *
     * <p>Each step zeroes at least one person, and the last step zeroes two (the remaining total is 0), so the result has at most (people with a non-zero net) minus 1 transfers. Cost is O(n log n) with two heaps. Ties go to the smaller user id, so the answer is deterministic.
     *
     * <p>It is NOT always the fewest transfers, see {@link #optimalCount}.
     *
     * @param nets user id to net (positive means the person is owed money, negative means owes); must add up to exactly 0
     * @return the transfers, in the order they were found
     * @throws IllegalArgumentException if the nets do not add up to 0
     */
    public static List<Transfer> greedy(Map<String, Long> nets) {
        long total = 0;
        for (long v : nets.values()) {
            total = Math.addExact(total, v);
        }
        if (total != 0) {
            throw new IllegalArgumentException("nets must add up to 0 but add up to " + total);
        }
        Comparator<Map.Entry<String, Long>> biggestFirst = Comparator
                .comparing((Map.Entry<String, Long> e) -> e.getValue()).reversed()
                .thenComparing(Map.Entry::getKey);
        PriorityQueue<Map.Entry<String, Long>> creditors = new PriorityQueue<>(biggestFirst);
        PriorityQueue<Map.Entry<String, Long>> debtors = new PriorityQueue<>(biggestFirst);
        for (Map.Entry<String, Long> e : new TreeMap<>(nets).entrySet()) {
            if (e.getValue() > 0) {
                creditors.add(Map.entry(e.getKey(), e.getValue()));
            } else if (e.getValue() < 0) {
                debtors.add(Map.entry(e.getKey(), -e.getValue()));
            }
        }
        List<Transfer> out = new ArrayList<>();
        while (!creditors.isEmpty() && !debtors.isEmpty()) {
            Map.Entry<String, Long> c = creditors.poll();
            Map.Entry<String, Long> d = debtors.poll();
            long pay = Math.min(c.getValue(), d.getValue());
            out.add(new Transfer(d.getKey(), c.getKey(), pay));
            if (c.getValue() > pay) {
                creditors.add(Map.entry(c.getKey(), c.getValue() - pay));
            }
            if (d.getValue() > pay) {
                debtors.add(Map.entry(d.getKey(), d.getValue() - pay));
            }
        }
        return out;
    }

    /**
     * The true minimum number of transfers, found exactly: the people split into groups that each net to 0 inside, a group of k people needs k-1 transfers, so the minimum is (non-zero people) minus (the most zero-sum groups we can carve out).
     *
     * <p>Bitmask DP: {@code best[mask]} is the most zero-sum groups that fit in the subset {@code mask}. Adding one person to a subset can finish at most one more group, and that happens exactly when the subset sums to 0. Cost O(2^n * n) time, O(2^n) memory, so it only works for small n.
     *
     * @param nets user id to net, adding up to 0
     * @return the minimum number of transfers that settles everyone
     * @throws ArithmeticException if a subset sum overflows a long
     * @throws IllegalArgumentException if the nets do not add up to 0 or more than {@link #MAX_EXACT} people are non-zero
     */
    public static int optimalCount(Map<String, Long> nets) {
        long[] v = nonZero(nets);
        int n = v.length;
        if (n > MAX_EXACT) {
            throw new IllegalArgumentException("exact solver is exponential: at most " + MAX_EXACT + " people");
        }
        int full = (1 << n) - 1;
        long[] sum = new long[full + 1];
        int[] best = new int[full + 1];
        for (int mask = 1; mask <= full; mask++) {
            int low = Integer.numberOfTrailingZeros(mask);
            sum[mask] = Math.addExact(sum[mask & (mask - 1)], v[low]);
            int b = 0;
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    b = Math.max(b, best[mask ^ (1 << i)]);
                }
            }
            best[mask] = b + (sum[mask] == 0 ? 1 : 0);
        }
        return n - best[full];
    }

    /**
     * Returns the non-zero nets as an array, after checking they add up to 0.
     *
     * @throws IllegalArgumentException if the total is not 0
     */
    static long[] nonZero(Map<String, Long> nets) {
        long total = 0;
        List<Long> list = new ArrayList<>();
        for (long v : new TreeMap<>(nets).values()) {
            total = Math.addExact(total, v);
            if (v != 0) {
                list.add(v);
            }
        }
        if (total != 0) {
            throw new IllegalArgumentException("nets must add up to 0 but add up to " + total);
        }
        return list.stream().mapToLong(Long::longValue).toArray();
    }
}
