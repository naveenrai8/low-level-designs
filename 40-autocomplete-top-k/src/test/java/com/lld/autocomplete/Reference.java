package com.lld.autocomplete;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The slow, obviously-correct model the trie is checked against: a plain map and a scan of every word on every query. */
final class Reference {

    final Map<String, Long> counts = new HashMap<>();

    /** Adds delta to the word's count and drops the word at 0. */
    void add(String word, long delta) {
        long now = counts.getOrDefault(word, 0L) + delta;
        if (now == 0) {
            counts.remove(word);
        } else {
            counts.put(word, now);
        }
    }

    /** Returns the best k words that start with the prefix, by scanning every word and sorting. */
    List<Suggestion> suggest(String prefix, int k) {
        List<Suggestion> all = new ArrayList<>();
        counts.forEach((w, c) -> {
            if (w.startsWith(prefix)) {
                all.add(new Suggestion(w, c));
            }
        });
        all.sort(Suggestion.ORDER);
        return new ArrayList<>(all.subList(0, Math.min(k, all.size())));
    }

    /** Returns the fuzzy answer by definition: words starting with q first, then words with a non-empty prefix within edit distance 1 of q, each group in rank order. */
    List<Suggestion> fuzzy(String q, int k) {
        List<Suggestion> exact = new ArrayList<>();
        List<Suggestion> near = new ArrayList<>();
        counts.forEach((w, c) -> {
            if (w.startsWith(q)) {
                exact.add(new Suggestion(w, c));
            } else if (hasPrefixWithin1(w, q)) {
                near.add(new Suggestion(w, c));
            }
        });
        exact.sort(Suggestion.ORDER);
        near.sort(Suggestion.ORDER);
        exact.addAll(near);
        return new ArrayList<>(exact.subList(0, Math.min(k, exact.size())));
    }

    /** True if some non-empty prefix of the word is within Levenshtein distance 1 of q. */
    static boolean hasPrefixWithin1(String w, String q) {
        for (int len = 1; len <= w.length(); len++) {
            if (levenshtein(w.substring(0, len), q) <= 1) {
                return true;
            }
        }
        return false;
    }

    /** Classic full-table Levenshtein distance (insert, delete, substitute; a swap of two letters costs 2). */
    static int levenshtein(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
        }
        return d[a.length()][b.length()];
    }
}
