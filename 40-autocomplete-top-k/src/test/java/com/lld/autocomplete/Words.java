package com.lld.autocomplete;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Makes seeded, real-ish synthetic words: 2 to 5 syllables from a fixed list, so words share prefixes the way natural words do. */
final class Words {

    private static final String[] SYLLABLES = {"ca", "re", "pro", "tion", "ing", "con", "de", "st", "ter", "an", "mi", "po", "la", "sub", "ex", "ver", "al", "ni", "ro", "ba", "ke", "ul", "or", "is", "ma", "tri", "gen", "ous", "ful", "ment"};

    /** Not instantiable: only the static factory is used. */
    private Words() {
    }

    /** Returns {@code n} distinct seeded words (fewer than n only if the generator runs out of variety, which it does not for n up to a few million). */
    static List<String> make(long seed, int n) {
        Random r = new Random(seed);
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        while (set.size() < n) {
            StringBuilder sb = new StringBuilder();
            int parts = 2 + r.nextInt(4);
            for (int i = 0; i < parts; i++) {
                sb.append(SYLLABLES[r.nextInt(SYLLABLES.length)]);
            }
            set.add(sb.toString());
        }
        return new ArrayList<>(set);
    }
}
