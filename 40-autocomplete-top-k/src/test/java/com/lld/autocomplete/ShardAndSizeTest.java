package com.lld.autocomplete;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The sharding router against the same brute force, and the memory shape of the trie. */
class ShardAndSizeTest {

    /** Builds a 4-shard router (route length 2) and a single reference, applies the same random updates, and compares plain and fuzzy answers after every step for short, route-length and long prefixes. */
    @Test
    void shardedMatchesBruteForceAfterEveryUpdate() {
        for (int seed = 0; seed < 60; seed++) {
            Random r = new Random(seed);
            List<TrieAutocomplete> shards = List.of(new TrieAutocomplete(3), new TrieAutocomplete(3), new TrieAutocomplete(3), new TrieAutocomplete(3));
            ShardedAutocomplete s = new ShardedAutocomplete(shards, 2);
            Reference ref = new Reference();
            for (int step = 0; step < 60; step++) {
                String w = rand(r, 1 + r.nextInt(5));
                long have = ref.counts.getOrDefault(w, 0L);
                if (have > 0 && r.nextInt(3) == 0) {
                    s.add(w, -have);
                    ref.add(w, -have);
                } else {
                    s.add(w, 2);
                    ref.add(w, 2);
                }
                for (String p : new String[] {"", "a", "ab", "abc", rand(r, 1 + r.nextInt(4)), "zz"}) {
                    for (int k : new int[] {1, 3, 50}) {
                        assertEquals(ref.suggest(p, k), s.suggest(p, k), "seed " + seed + " plain '" + p + "' k=" + k);
                        assertEquals(ref.fuzzy(p, k), s.suggestFuzzy(p, k), "seed " + seed + " fuzzy '" + p + "' k=" + k);
                    }
                }
                assertEquals(ref.counts.size(), s.size());
            }
        }
    }

    /** A prefix at least as long as the route key touches exactly one shard; a shorter one touches all four. */
    @Test
    void longPrefixGoesToOneShard() {
        int[] calls = new int[4];
        List<Autocomplete> shards = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int id = i;
            shards.add(new Spy(new TrieAutocomplete(3), prefix -> calls[id]++));
        }
        ShardedAutocomplete s = new ShardedAutocomplete(shards, 2);
        s.add("apple", 1);
        s.suggest("app", 3);
        assertEquals(1, java.util.Arrays.stream(calls).sum());
        s.suggest("ap", 3);
        assertEquals(2, java.util.Arrays.stream(calls).sum());
        s.suggest("a", 3);
        assertEquals(6, java.util.Arrays.stream(calls).sum());
    }

    /** Makes a random word of the given length over a, b, c. */
    private static String rand(Random r, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append("abc".charAt(r.nextInt(3)));
        }
        return sb.toString();
    }

    /** On 20,000 seeded synthetic words the trie has a bounded number of nodes and cached slots per word; prints the measured numbers. */
    @Test
    void nodesPerWord() {
        List<String> words = Words.make(11, 20_000);
        TrieAutocomplete t = new TrieAutocomplete(10);
        long letters = 0;
        for (String w : words) {
            t.add(w, 1);
            letters += w.length();
        }
        double nodesPerWord = t.nodeCount() / (double) words.size();
        double slotsPerWord = t.cacheSlots() / (double) words.size();
        System.out.printf("memory: %d words, %.1f letters/word, %.2f nodes/word, %.2f cached slots/word%n", words.size(), letters / (double) words.size(), nodesPerWord, slotsPerWord);
        assertTrue(nodesPerWord < letters / (double) words.size(), "sharing prefixes must beat one node per letter");
        assertTrue(slotsPerWord <= 10 * nodesPerWord);
    }

    /** The router refuses no shards and a route length below 1. */
    @Test
    void constructorValidation() {
        List<TrieAutocomplete> one = List.of(new TrieAutocomplete(3));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new ShardedAutocomplete(List.of(), 2));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new ShardedAutocomplete(one, 0));
        assertEquals(0, new ShardedAutocomplete(one, 1).size());
    }
}
