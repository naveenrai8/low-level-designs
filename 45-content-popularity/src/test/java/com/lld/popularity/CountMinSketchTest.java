package com.lld.popularity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Tests the Count-Min sketch: sizing, one-sided error, measured error on a skewed stream, determinism and memory. */
class CountMinSketchTest {

    /** Width and depth come from the formulas w = ceil(e/eps), d = ceil(ln(1/delta)). */
    @Test
    void sizingFormulas() {
        assertEquals(272, CountMinSketch.widthFor(0.01));
        assertEquals(5, CountMinSketch.depthFor(0.01));
        assertEquals(3, CountMinSketch.depthFor(0.05));
        assertThrows(IllegalArgumentException.class, () -> CountMinSketch.widthFor(0));
        assertThrows(IllegalArgumentException.class, () -> CountMinSketch.depthFor(1));
    }

    /** The worked README example: a few events then a query, an unseen item reads 0 when there are no collisions. */
    @Test
    void smallExample() {
        CountMinSketch s = new CountMinSketch(0.001, 0.01, 1);
        s.add("a", 2);
        s.add("b", 1);
        assertEquals(2, s.estimate("a"));
        assertEquals(1, s.estimate("b"));
        assertEquals(0, s.estimate("zzz"));
        assertEquals(3, s.total());
    }

    /** Clearing resets both the counters and the running total. */
    @Test
    void clearResetsEverything() {
        CountMinSketch s = new CountMinSketch(0.01, 0.01, 1);
        s.add("a", 5);
        s.clear();
        assertEquals(0, s.estimate("a"));
        assertEquals(0, s.total());
    }

    /** Memory in bytes is exactly width * depth * 8. */
    @Test
    void memoryIsStated() {
        CountMinSketch s = new CountMinSketch(0.01, 0.01, 1);
        assertEquals(272L * 5 * 8, s.counterBytes());
    }

    /** Same seed gives the same table, so results are repeatable. */
    @Test
    void seededHashIsRepeatable() {
        CountMinSketch a = new CountMinSketch(0.05, 0.05, 7);
        CountMinSketch b = new CountMinSketch(0.05, 0.05, 7);
        for (int i = 0; i < 500; i++) {
            a.add("k" + i % 37, 1);
            b.add("k" + i % 37, 1);
        }
        for (int i = 0; i < 60; i++) {
            assertEquals(a.estimate("k" + i), b.estimate("k" + i));
        }
    }

    /** Over a crowded sketch (many more items than columns) every single item is checked: the estimate is never below the truth. */
    @Test
    void neverUnderestimatesExhaustively() {
        CountMinSketch s = new CountMinSketch(0.05, 0.1, 42);
        Map<String, Long> truth = new HashMap<>();
        Random rnd = new Random(5);
        for (int i = 0; i < 20_000; i++) {
            String id = "item" + rnd.nextInt(2000);
            s.add(id, 1);
            truth.merge(id, 1L, Long::sum);
        }
        for (Map.Entry<String, Long> e : truth.entrySet()) {
            assertTrue(s.estimate(e.getKey()) >= e.getValue(), e.getKey());
        }
    }

    /**
     * Measures the overestimate on a Zipf-like stream (item rank r has weight 1/r, 5000 items, 200000 events, seed 11, eps 0.01, delta 0.01) and prints max and mean next to the eps*N bound. The bound holds per item with probability 1-delta, so the test allows at most delta*items exceptions (plus a small slack).
     */
    @Test
    void measuredErrorOnZipfStream() {
        double eps = 0.01;
        double delta = 0.01;
        int items = 5000;
        int events = 200_000;
        CountMinSketch s = new CountMinSketch(eps, delta, 11);
        double[] cdf = new double[items];
        double sum = 0;
        for (int r = 0; r < items; r++) {
            sum += 1.0 / (r + 1);
            cdf[r] = sum;
        }
        Random rnd = new Random(11);
        long[] truth = new long[items];
        for (int i = 0; i < events; i++) {
            double u = rnd.nextDouble() * sum;
            int lo = 0;
            int hi = items - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (cdf[mid] < u) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            truth[lo]++;
            s.add("z" + lo, 1);
        }
        long max = 0;
        double total = 0;
        int over = 0;
        double bound = eps * events;
        for (int r = 0; r < items; r++) {
            long err = s.estimate("z" + r) - truth[r];
            assertTrue(err >= 0);
            max = Math.max(max, err);
            total += err;
            if (err > bound) {
                over++;
            }
        }
        System.out.printf("CMS zipf: N=%d width=%d depth=%d bound=%.0f maxErr=%d meanErr=%.2f overBound=%d/%d%n", events, s.width(), s.depth(), bound, max, total / items, over, items);
        assertTrue(over <= items * delta * 2 + 5, "too many items over the eps*N bound: " + over);
    }
}
