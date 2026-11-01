package com.lld.dispatch;

import java.util.Arrays;

/**
 * Exact minimum-total-cost assignment (the Hungarian method, O(n^2 m) for n requests and m drivers).
 * <p>It first serves as many requests as possible and, among those, picks the cheapest total. Forbidden pairs cost 2^50, far more than any sum of real costs, so they are used only when unavoidable and are then dropped from the answer.
 * <p>The matcher keeps batches to 256 requests, so potentials stay below 2^58 and never overflow.
 */
public final class OptimalAssigner implements BatchAssigner {

    private static final long INF = Long.MAX_VALUE / 4;

    /** Runs the Hungarian method, transposing first when there are more requests than drivers. */
    @Override
    public int[] assign(long[][] cost) {
        int n = cost.length;
        int[] out = new int[n];
        Arrays.fill(out, -1);
        if (n == 0 || cost[0].length == 0) {
            return out;
        }
        int m = cost[0].length;
        if (n <= m) {
            int[] rowToCol = solve(cost, n, m);
            for (int i = 0; i < n; i++) {
                out[i] = cost[i][rowToCol[i]] < FORBIDDEN ? rowToCol[i] : -1;
            }
        } else {
            long[][] t = new long[m][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < m; j++) {
                    t[j][i] = cost[i][j];
                }
            }
            int[] colToRow = solve(t, m, n);
            for (int j = 0; j < m; j++) {
                int i = colToRow[j];
                if (cost[i][j] < FORBIDDEN) {
                    out[i] = j;
                }
            }
        }
        return out;
    }

    /** Hungarian method for a table with at most as many rows as columns; returns the column chosen for each row. */
    private static int[] solve(long[][] a, int n, int m) {
        long[] u = new long[n + 1];
        long[] v = new long[m + 1];
        int[] p = new int[m + 1];
        int[] way = new int[m + 1];
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            long[] minv = new long[m + 1];
            Arrays.fill(minv, INF);
            boolean[] used = new boolean[m + 1];
            do {
                used[j0] = true;
                int i0 = p[j0];
                long delta = INF;
                int j1 = 0;
                for (int j = 1; j <= m; j++) {
                    if (!used[j]) {
                        long cur = a[i0 - 1][j - 1] - u[i0] - v[j];
                        if (cur < minv[j]) {
                            minv[j] = cur;
                            way[j] = j0;
                        }
                        if (minv[j] < delta) {
                            delta = minv[j];
                            j1 = j;
                        }
                    }
                }
                for (int j = 0; j <= m; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }
        int[] rowToCol = new int[n];
        for (int j = 1; j <= m; j++) {
            if (p[j] != 0) {
                rowToCol[p[j] - 1] = j - 1;
            }
        }
        return rowToCol;
    }
}
