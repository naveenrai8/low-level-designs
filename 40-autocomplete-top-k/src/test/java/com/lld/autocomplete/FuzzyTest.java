package com.lld.autocomplete;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Edit-distance-1 behaviour: each kind of typo, ranking, the brute-force differential, and the bound on work. */
class FuzzyTest {

    /** Turns a result list into plain words. */
    private static List<String> words(List<Suggestion> l) {
        return l.stream().map(Suggestion::word).toList();
    }

    /** Builds an index with one word per kind of case. */
    private static TrieAutocomplete sample() {
        TrieAutocomplete t = new TrieAutocomplete(5);
        t.add("hello", 5);
        t.add("help", 4);
        t.add("world", 9);
        t.add("hero", 2);
        return t;
    }

    /** A wrong letter (substitution): "hxl" is one substitution away from the prefix "hel". */
    @Test
    void substitution() {
        assertEquals(List.of("hello", "help"), words(sample().suggestFuzzy("hxl", 5).stream().filter(s -> s.word().startsWith("hel")).toList()));
    }

    /** A missing letter (the typist skipped one): "hllo" is one insertion away from "hello". */
    @Test
    void missingLetter() {
        assertTrue(words(sample().suggestFuzzy("hllo", 5)).contains("hello"));
    }

    /** An extra letter (the typist added one): "helllo" is one deletion away from "hello". */
    @Test
    void extraLetter() {
        assertTrue(words(sample().suggestFuzzy("helllo", 5)).contains("hello"));
    }

    /** A swapped pair is NOT matched as a whole: this is Levenshtein, where a swap costs 2, not Damerau, where it costs 1. (Because only a prefix of the word has to be close, "fomr" still finds "form" through the prefix "for"; see the README.) */
    @Test
    void transpositionIsOutOfScope() {
        TrieAutocomplete t = new TrieAutocomplete(5);
        t.add("abcd", 1);
        assertEquals(List.of(), t.suggestFuzzy("bacd", 5));
        assertEquals(2, Reference.levenshtein("bacd", "abcd"));
        t.add("form", 1);
        assertEquals(List.of("form"), words(t.suggestFuzzy("fomr", 5)));
    }

    /** Words that really start with the query come before fuzzy ones, even when a fuzzy word is far more popular. */
    @Test
    void exactPrefixMatchesRankAboveFuzzyOnes() {
        TrieAutocomplete t = new TrieAutocomplete(5);
        t.add("cat", 1);
        t.add("cart", 1000);
        t.add("cats", 2);
        assertEquals(List.of("cats", "cat", "cart"), words(t.suggestFuzzy("cat", 3)));
        assertEquals(List.of("cats", "cat"), words(t.suggestFuzzy("cat", 2)));
    }

    /** Two typos away is not enough. */
    @Test
    void distanceTwoIsRejected() {
        assertEquals(List.of(), sample().suggestFuzzy("xxlo", 5));
    }

    /** An empty prefix means everything, a long prefix is refused, and a null prefix is refused. */
    @Test
    void edgeInputs() {
        assertEquals(4, sample().suggestFuzzy("", 10).size());
        assertThrows(IllegalArgumentException.class, () -> sample().suggestFuzzy("a".repeat(257), 3));
        assertThrows(IllegalArgumentException.class, () -> sample().suggestFuzzy(null, 3));
        assertEquals(List.of(), sample().suggestFuzzy("hxl", 0));
    }

    /** 300 seeds: after every update, fuzzy answers equal the definition (prefix within Levenshtein 1) computed with a full table, for many queries and k values. */
    @Test
    void fuzzyMatchesBruteForceAfterEveryUpdate() {
        int nonEmptyFuzzyOnly = 0;
        for (int seed = 0; seed < 300; seed++) {
            Random r = new Random(1000 + seed);
            TrieAutocomplete t = new TrieAutocomplete(2 + seed % 3);
            Reference ref = new Reference();
            List<String> known = new ArrayList<>();
            for (int step = 0; step < 40; step++) {
                String w = randomWord(r, 1 + r.nextInt(5));
                long have = ref.counts.getOrDefault(w, 0L);
                if (have > 0 && r.nextInt(3) == 0) {
                    t.add(w, -have);
                    ref.add(w, -have);
                } else {
                    long d = 1 + r.nextInt(5);
                    t.add(w, d);
                    ref.add(w, d);
                }
                known.add(w);
                for (int q = 0; q < 4; q++) {
                    String query = q == 0 ? known.get(r.nextInt(known.size())) : randomWord(r, r.nextInt(6));
                    for (int k : new int[] {1, 3, 4, 100}) {
                        List<Suggestion> want = ref.fuzzy(query, k);
                        assertEquals(want, t.suggestFuzzy(query, k), "seed " + seed + " step " + step + " query '" + query + "' k=" + k);
                        if (want.size() > ref.suggest(query, k).size()) {
                            nonEmptyFuzzyOnly++;
                        }
                    }
                }
            }
        }
        assertTrue(nonEmptyFuzzyOnly > 5_000, "generator too weak: " + nonEmptyFuzzyOnly);
    }

    /** Makes a random word of the given length over a, b, c. */
    private static String randomWord(Random r, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append("abc".charAt(r.nextInt(3)));
        }
        return sb.toString();
    }

    /** Builds a dense trie: 20,000 random 3-to-14 letter words over 26 letters, plus two 200-letter runs of "a" (one with a trailing "b" on every prefix) so a long query keeps matching itself. */
    private static TrieAutocomplete denseTrie() {
        TrieAutocomplete t = new TrieAutocomplete(3);
        Random r = new Random(5);
        for (int i = 0; i < 20_000; i++) {
            StringBuilder sb = new StringBuilder();
            int len = 3 + r.nextInt(12);
            for (int j = 0; j < len; j++) {
                sb.append((char) ('a' + r.nextInt(26)));
            }
            t.add(sb.toString(), 1 + r.nextInt(9));
        }
        String run = "a".repeat(200);
        for (int i = 1; i <= 200; i++) {
            t.add(run.substring(0, i), 1);
            t.add(run.substring(0, i) + "b", 1);
        }
        return t;
    }

    /**
     * The fuzzy walk costs one 3-cell row per child it looks at, so work is counted in rows. Empirical bound on this dense trie, not a proof: rows stay far below the node count, and growing the query 10 times (20 to 200 letters) grows the rows less than 12 times, which is linear and not quadratic or exponential. A k above the cache forces subtree scans; the nodes they look at are counted too and stay below the node count.
     */
    @Test
    void fuzzyWorkIsBounded() {
        TrieAutocomplete t = denseTrie();
        int nodes = t.nodeCount();
        int[] rows = new int[4];
        int i = 0;
        for (int m : new int[] {5, 20, 100, 200}) {
            int[] stats = new int[2];
            t.fuzzy("a".repeat(m) + "z", 1003, stats);
            rows[i++] = stats[0];
            assertTrue(stats[0] < nodes / 2, "m=" + m + " rows=" + stats[0] + " nodes=" + nodes);
            assertTrue(stats[1] < nodes, "m=" + m + " scanned=" + stats[1] + " nodes=" + nodes);
        }
        assertTrue(rows[3] < 12L * rows[1], "rows grew faster than linear: m=20 " + rows[1] + ", m=200 " + rows[3]);
        int[] small = new int[2];
        t.fuzzy("qxz", 5, small);
        assertTrue(small[0] < nodes / 10, "short query looked at " + small[0] + " rows of " + nodes + " nodes");
    }

    /** With k at most the cache size, no query (plain or fuzzy) ever scans a subtree: this is the O(L + K) fast path. With a bigger k the scan counter moves. */
    @Test
    void cachedPathNeverScans() {
        TrieAutocomplete t = denseTrie();
        for (String q : new String[] {"", "a", "ab", "xq", "zzz", "aaaa", "bcd"}) {
            t.suggest(q, 3);
            t.suggestFuzzy(q, 3);
        }
        assertEquals(0, t.scans());
        t.suggest("a", 4);
        assertEquals(1, t.scans());
        t.suggestFuzzy("xq", 50);
        assertTrue(t.scans() > 1);
    }

    /** The longest allowed fuzzy prefix (256) works and one more is refused: both sides of the boundary. */
    @Test
    void prefixLengthBoundaryIsPinned() {
        assertDoesNotThrow(() -> sample().suggestFuzzy("a".repeat(256), 3));
        assertThrows(IllegalArgumentException.class, () -> sample().suggestFuzzy("a".repeat(257), 3));
    }
}
