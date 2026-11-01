package com.lld.spreadsheet;

import java.util.Arrays;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Optional timing of one incremental edit against a full recompute. Skipped unless run with -Dbench=true. Timing is a measurement only: nothing is asserted.
 */
class BenchmarkTest {

    /** Name of chain cell i (1-based) on a 26-column sheet of 400 rows. */
    private static String name(int i) {
        return new CellId((i - 1) / 400, (i - 1) % 400 + 1).toString();
    }

    /** Median of the samples. */
    private static long median(long[] a) {
        long[] c = a.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }

    /** Prints median microseconds for editing the first cell of an N-cell chain, the hub of an N-cell fan-out, and a leaf, versus recomputing all N cells. */
    @Test
    void incrementalVersusFull() {
        Assumptions.assumeTrue(Boolean.getBoolean("bench"), "run with -Dbench=true");
        int runs = 25;
        for (int n : new int[] {1000, 5000, 10000}) {
            Sheet chain = new Sheet(new Grid(400));
            Sheet fan = new Sheet(new Grid(400));
            for (int i = 1; i <= n; i++) {
                chain.set(name(i), i == 1 ? "1" : "=" + name(i - 1) + "+1");
                fan.set(name(i), i == 1 ? "1" : "=" + name(1) + "+" + i);
            }
            System.out.printf("N=%d chain-head=%dus chain-tail=%dus fan-hub=%dus fan-leaf=%dus full=%dus%n", n,
                    time(runs, k -> chain.set(name(1), Integer.toString(k + 2))) / 1000,
                    time(runs, k -> chain.set(name(n), "=" + k)) / 1000,
                    time(runs, k -> fan.set(name(1), Integer.toString(k + 2))) / 1000,
                    time(runs, k -> fan.set(name(n), "=" + k)) / 1000,
                    time(runs, k -> chain.recomputeAll()) / 1000);
        }
    }

    /** Warms up, then runs the action {@code runs} times and returns the median nanoseconds. */
    private static long time(int runs, java.util.function.IntConsumer action) {
        for (int k = 0; k < 10; k++) {
            action.accept(k);
        }
        long[] t = new long[runs];
        for (int k = 0; k < runs; k++) {
            long a = System.nanoTime();
            action.accept(k + 100);
            t[k] = System.nanoTime() - a;
        }
        return median(t);
    }
}
