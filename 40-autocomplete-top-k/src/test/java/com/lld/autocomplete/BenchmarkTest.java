package com.lld.autocomplete;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** An opt-in measurement, off by default. Run it with {@code -Dautocomplete.bench=true}. It reads {@code System.nanoTime}, so it measures this toy on this machine and is not a correctness test. */
@EnabledIfSystemProperty(named = "autocomplete.bench", matches = "true")
@Timeout(600)
class BenchmarkTest {

    /** Returns the heap in use right now, after asking for a garbage collection twice. */
    private static long heap() {
        System.gc();
        System.gc();
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    /**
     * Prints, for N = 10k, 100k and 1M seeded words, the build time, the suggest and fuzzy cost per call, and the heap per word.
     *
     * <p>Build time is the median of 3 runs. Suggest latency is found by timing a whole loop of 20,000 queries and dividing, because one {@code nanoTime} tick is about 40 ns on Apple Silicon and timing single calls would only show the tick. Fuzzy uses a leading typo. Heap is measured above a baseline taken after the word list exists and before the build.
     */
    @Test
    void buildAndSuggestLatency() {
        for (int n : new int[] {10_000, 100_000, 1_000_000}) {
            List<String> words = Words.make(3, n);
            Random r = new Random(9);
            long baseline = heap();
            List<Double> builds = new ArrayList<>();
            TrieAutocomplete t = null;
            for (int run = 0; run < 3; run++) {
                long t0 = System.nanoTime();
                t = new TrieAutocomplete(10);
                for (String w : words) {
                    t.add(w, 1 + (w.hashCode() & 15));
                }
                builds.add((System.nanoTime() - t0) / 1e6);
            }
            Collections.sort(builds);
            long perWord = (heap() - baseline) / n;
            String[] queries = new String[20_000];
            for (int i = 0; i < queries.length; i++) {
                String w = words.get(r.nextInt(n));
                queries[i] = w.substring(0, Math.min(w.length(), r.nextInt(5)));
            }
            String[] fq = new String[2_000];
            for (int i = 0; i < fq.length; i++) {
                String w = words.get(r.nextInt(n));
                fq[i] = "x" + w.substring(0, Math.min(w.length(), 4));
            }
            long sink = 0;
            for (int rep = 0; rep < 3; rep++) {
                for (String q : queries) {
                    sink += t.suggest(q, 10).size();
                }
                for (String q : fq) {
                    sink += t.suggestFuzzy(q, 10).size();
                }
            }
            List<Double> plain = new ArrayList<>();
            List<Double> fuzzy = new ArrayList<>();
            for (int run = 0; run < 5; run++) {
                long t0 = System.nanoTime();
                for (String q : queries) {
                    sink += t.suggest(q, 10).size();
                }
                plain.add((System.nanoTime() - t0) / (double) queries.length);
                t0 = System.nanoTime();
                for (String q : fq) {
                    sink += t.suggestFuzzy(q, 10).size();
                }
                fuzzy.add((System.nanoTime() - t0) / 1000.0 / fq.length);
            }
            Collections.sort(plain);
            Collections.sort(fuzzy);
            System.out.printf("N=%,d nodes=%,d build median %.0f ms (min %.0f, max %.0f), suggest ns/call median %.0f (min %.0f, max %.0f), fuzzy us/call median %.1f (min %.1f, max %.1f), heap above baseline %d bytes/word (sink %d)%n", n, t.nodeCount(), builds.get(1), builds.get(0), builds.get(2), plain.get(2), plain.get(0), plain.get(4), fuzzy.get(2), fuzzy.get(0), fuzzy.get(4), perWord, sink);
        }
    }
}
