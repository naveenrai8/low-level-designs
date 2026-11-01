package com.lld.autocomplete;

import java.util.Comparator;

/**
 * One suggested word and how many times it has been counted.
 *
 * <p>The ranking rule lives here, in one place: a higher count comes first, and equal counts are ordered by the word itself (plain {@link String#compareTo}, so "apple" before "apply"). Because ties are broken the same way everywhere, two correct implementations always give the same answer.
 *
 * @param word  the suggested word
 * @param count how many times it was counted, always at least 1
 */
public record Suggestion(String word, long count) {

    /** The ranking order: highest count first, then the smaller word first. */
    public static final Comparator<Suggestion> ORDER = (a, b) -> {
        int byCount = Long.compare(b.count, a.count);
        return byCount != 0 ? byCount : a.word.compareTo(b.word);
    };
}
