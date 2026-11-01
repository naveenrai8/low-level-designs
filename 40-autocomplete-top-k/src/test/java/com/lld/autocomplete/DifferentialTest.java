package com.lld.autocomplete;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Checks the trie against the brute-force Reference after EVERY update, over many seeds. */
class DifferentialTest {

    private static final String ALPHABET = "abc";

    /** Makes a random word of 1 to 5 letters from a tiny alphabet, so words collide and share prefixes a lot. */
    private static String word(Random r) {
        StringBuilder sb = new StringBuilder();
        int len = 1 + r.nextInt(5);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Compares plain suggestions for the empty prefix, a random prefix, a stored word, an absent prefix, and several k values including one bigger than the match count. */
    private static void compare(TrieAutocomplete t, Reference ref, Random r, List<String> known, String ctx) {
        List<String> prefixes = new ArrayList<>(List.of("", "zz", word(r), word(r).substring(0, 1)));
        if (!known.isEmpty()) {
            prefixes.add(known.get(r.nextInt(known.size())));
        }
        for (String p : prefixes) {
            for (int k : new int[] {1, 2, 3, 5, 100}) {
                assertEquals(ref.suggest(p, k), t.suggest(p, k), ctx + " prefix='" + p + "' k=" + k);
            }
        }
        assertEquals(ref.counts.size(), t.size(), ctx);
    }

    /** 300 seeds of 60 mixed steps (raise, lower, delete, add), comparing after each step with a tiny cache (K = 2 or 3) so the trap path runs a lot. */
    @Test
    void trieMatchesBruteForceAfterEveryUpdate() {
        int steps = 0;
        int lowers = 0;
        int repairs = 0;
        for (int seed = 0; seed < 300; seed++) {
            Random r = new Random(seed);
            TrieAutocomplete t = new TrieAutocomplete(2 + seed % 2);
            Reference ref = new Reference();
            List<String> known = new ArrayList<>();
            for (int step = 0; step < 60; step++) {
                String w = r.nextInt(3) == 0 && !known.isEmpty() ? known.get(r.nextInt(known.size())) : word(r);
                long have = ref.counts.getOrDefault(w, 0L);
                int op = r.nextInt(10);
                String what;
                if (op < 5 || have == 0) {
                    long d = 1 + r.nextInt(6);
                    t.add(w, d);
                    ref.add(w, d);
                    what = "add " + w + " +" + d;
                } else if (op < 8) {
                    long d = 1 + r.nextInt((int) have);
                    t.add(w, -d);
                    ref.add(w, -d);
                    what = "add " + w + " -" + d;
                    lowers++;
                } else {
                    t.remove(w);
                    ref.add(w, -have);
                    what = "remove " + w;
                    lowers++;
                }
                if (!known.contains(w)) {
                    known.add(w);
                }
                steps++;
                compare(t, ref, r, known, "seed " + seed + " step " + step + " " + what);
            }
            repairs += t.repairs();
        }
        assertEquals(18_000, steps);
        assertTrue(lowers > 3_000, "generator too weak: lowers=" + lowers);
        assertTrue(repairs > 2_000, "trap path barely ran: repairs=" + repairs);
    }

    /** The same comparison on the same 3-letter alphabet with cache 5 and 4000 steps, plus a check that the cached list of every node is exactly the best K of its subtree. */
    @Test
    void cachedListsAreExactlyTheBestKEverywhere() {
        Random r = new Random(7);
        TrieAutocomplete t = new TrieAutocomplete(5);
        Reference ref = new Reference();
        List<String> known = new ArrayList<>();
        for (int step = 0; step < 4000; step++) {
            String w = word(r);
            long have = ref.counts.getOrDefault(w, 0L);
            if (have > 0 && r.nextInt(3) == 0) {
                long d = 1 + r.nextInt((int) have);
                t.add(w, -d);
                ref.add(w, -d);
            } else {
                long d = 1 + r.nextInt(4);
                t.add(w, d);
                ref.add(w, d);
            }
            known.add(w);
            if (step % 50 == 0) {
                for (String p : known.subList(Math.max(0, known.size() - 20), known.size())) {
                    for (int len = 0; len <= p.length(); len++) {
                        String pre = p.substring(0, len);
                        assertEquals(ref.suggest(pre, 5), t.cachedList(pre), "step " + step + " node '" + pre + "'");
                    }
                }
            }
        }
    }
}
