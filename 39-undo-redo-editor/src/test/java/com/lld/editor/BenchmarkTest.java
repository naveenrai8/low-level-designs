package com.lld.editor;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** An opt-in measurement, off by default. Run it with {@code -Deditor.bench=true}. It reads {@code System.nanoTime}, so it measures this toy, not a real editor. Only random middle inserts are timed; reading the text back is not. */
@EnabledIfSystemProperty(named = "editor.bench", matches = "true")
@Timeout(600)
class BenchmarkTest {

    private static long sink;

    /** For 10k, 20k and 40k inserts into a 1 MB text, prints the median of 7 runs (after 3 warm-ups) for the piece table and for StringBuilder, to show where the flat piece list stops winning. */
    @Test
    void middleInsertsOnOneMegabyte() {
        String base = "abcdefghij".repeat(100_000);
        for (int edits : new int[] {10_000, 20_000, 40_000}) {
            for (int i = 0; i < 3; i++) {
                piece(base, edits);
                naive(base, edits);
            }
            double[] p = new double[7];
            double[] n = new double[7];
            for (int i = 0; i < 7; i++) {
                p[i] = piece(base, edits);
                n[i] = naive(base, edits);
            }
            Arrays.sort(p);
            Arrays.sort(n);
            System.out.printf("1 MB, %d inserts: piece table median %.1f ms (min %.1f), StringBuilder median %.1f ms (min %.1f), sink %d%n", edits, p[3], p[0], n[3], n[0], sink);
        }
    }

    /** Applies seeded random inserts to a fresh piece table and returns milliseconds. The final length is stored in a field so the work cannot be removed. */
    private static double piece(String base, int edits) {
        PieceTable t = new PieceTable(base);
        Random r = new Random(1);
        long t0 = System.nanoTime();
        for (int i = 0; i < edits; i++) {
            t.insert(r.nextInt(t.length() + 1), "xy");
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        sink += t.length() + t.pieceCount();
        return ms;
    }

    /** Applies the same inserts to a StringBuilder and returns milliseconds. */
    private static double naive(String base, int edits) {
        StringBuilder sb = new StringBuilder(base);
        Random r = new Random(1);
        long t0 = System.nanoTime();
        for (int i = 0; i < edits; i++) {
            sb.insert(r.nextInt(sb.length() + 1), "xy");
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        sink += sb.length();
        return ms;
    }
}
