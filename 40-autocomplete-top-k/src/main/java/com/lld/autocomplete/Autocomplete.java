package com.lld.autocomplete;

import java.util.List;

/**
 * A word-frequency index that answers "which K words that start with these letters are the most popular?".
 *
 * <p>Alphabet: any {@code char} (a word is a sequence of UTF-16 code units). Matching is case-sensitive and nothing is normalised: "Apple" and "apple" are different words, and Unicode case folding is out of scope. Callers lower-case first if they want that.
 *
 * <p>Error contract, the same for every implementation: a {@code null} or empty word, a {@code null} prefix, a negative {@code k} and a zero {@code delta} throw {@link IllegalArgumentException}. A change that would push a count below zero also throws, and changes nothing. An empty prefix is allowed and means "the most popular words overall".
 *
 * <p>Ranking: higher count first, equal counts by the word in {@link String#compareTo} order (see {@link Suggestion#ORDER}).
 */
public interface Autocomplete {

    /** Adds {@code delta} to the word's count. A positive delta counts the word (creating it if new). A negative delta lowers it, and a count that reaches 0 removes the word. Throws if the count would go below 0, or if the sum would overflow a long; nothing changes then. */
    void add(String word, long delta);

    /** Removes the word whatever its count and returns true, or returns false if it was not there. */
    boolean remove(String word);

    /** Returns the word's count, or 0 if it is not there. */
    long count(String word);

    /** Returns up to {@code k} words that start with {@code prefix}, best first. Fewer come back if fewer match. */
    List<Suggestion> suggest(String prefix, int k);

    /** Returns up to {@code k} words, best first, where words that start with {@code prefix} come before words that only start with something one typo away from it. The prefix is limited to 256 characters. */
    List<Suggestion> suggestFuzzy(String prefix, int k);

    /** Returns how many distinct words are stored. */
    int size();
}
