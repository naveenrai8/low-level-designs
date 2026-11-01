package com.lld.dispatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Greedy by best global pair: repeatedly take the cheapest remaining (request, driver) pair anywhere in the table, then cross out that row and column.
 * <p>Fast (sort the pairs once) but not optimal: taking the cheapest pair can force an expensive one later. Ties go to the lower row, then the lower column, so the answer is the same on every run.
 */
public final class GreedyAssigner implements BatchAssigner {

    /** Sorts every allowed pair by cost and takes each pair whose row and column are still free. */
    @Override
    public int[] assign(long[][] cost) {
        int n = cost.length;
        int[] out = new int[n];
        java.util.Arrays.fill(out, -1);
        List<long[]> pairs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < cost[i].length; j++) {
                if (cost[i][j] < FORBIDDEN) {
                    pairs.add(new long[] {cost[i][j], i, j});
                }
            }
        }
        pairs.sort(Comparator.<long[]>comparingLong(p -> p[0]).thenComparingLong(p -> p[1]).thenComparingLong(p -> p[2]));
        int cols = n == 0 ? 0 : cost[0].length;
        boolean[] colUsed = new boolean[cols];
        for (long[] p : pairs) {
            int i = (int) p[1];
            int j = (int) p[2];
            if (out[i] == -1 && !colUsed[j]) {
                out[i] = j;
                colUsed[j] = true;
            }
        }
        return out;
    }
}
