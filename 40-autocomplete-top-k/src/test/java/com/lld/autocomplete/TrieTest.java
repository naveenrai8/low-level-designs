package com.lld.autocomplete;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Hand-written behaviour tests: the worked example, ties, validation, and the cached-top-K trap. */
class TrieTest {

    /** Turns a result list into "word:count" strings so assertions read easily. */
    private static List<String> show(List<Suggestion> l) {
        return l.stream().map(s -> s.word() + ":" + s.count()).toList();
    }

    /** Builds the README example: five words, K = 2. */
    private static TrieAutocomplete example() {
        TrieAutocomplete t = new TrieAutocomplete(2);
        t.add("apple", 5);
        t.add("apply", 3);
        t.add("app", 3);
        t.add("ape", 9);
        t.add("bat", 1);
        return t;
    }

    /** The worked example: top-2 under "ap", under "app", the empty prefix, and an absent prefix. */
    @Test
    void workedExample() {
        TrieAutocomplete t = example();
        assertEquals(List.of("ape:9", "apple:5"), show(t.suggest("ap", 2)));
        assertEquals(List.of("apple:5", "app:3"), show(t.suggest("app", 2)));
        assertEquals(List.of("ape:9", "apple:5"), show(t.suggest("", 2)));
        assertEquals(List.of(), t.suggest("zzz", 5));
        assertEquals(5, t.size());
    }

    /** k larger than the number of matches returns every match; k larger than the cache still works through a scan. */
    @Test
    void kLargerThanMatchesAndThanCache() {
        TrieAutocomplete t = example();
        assertEquals(List.of("ape:9", "apple:5", "app:3", "apply:3"), show(t.suggest("ap", 50)));
        assertEquals(5, t.suggest("", 50).size());
        assertEquals(List.of(), t.suggest("ap", 0));
    }

    /** Equal counts are ordered by the word itself: "app" before "apply". */
    @Test
    void tiesAreBrokenByWord() {
        TrieAutocomplete t = new TrieAutocomplete(5);
        t.add("zed", 2);
        t.add("apply", 2);
        t.add("app", 2);
        assertEquals(List.of("app:2", "apply:2", "zed:2"), show(t.suggest("", 5)));
    }

    /** A rise changes only the path of that word, and a higher count moves a word to the front of every list above it. */
    @Test
    void incrementMovesWordUp() {
        TrieAutocomplete t = example();
        t.add("apply", 10);
        assertEquals(List.of("apply:13", "ape:9"), show(t.suggest("ap", 2)));
        assertEquals(13, t.count("apply"));
    }

    /** THE TRAP: "app" was never in the cached top-2 of "ap" (ape, apple beat it). When both leaders drop, "app" and "apply" must come in, so the list has to be rebuilt. */
    @Test
    void decrementPullsInAWordThatWasNeverCached() {
        TrieAutocomplete t = example();
        assertFalse(t.cachedAt("ap", "app"));
        assertFalse(t.cachedAt("ap", "apply"));
        t.add("ape", -9);
        t.add("apple", -4);
        assertEquals(List.of("app:3", "apply:3"), show(t.suggest("ap", 2)));
        assertTrue(t.repairs() > 0);
        assertTrue(t.cachedAt("ap", "app"));
    }

    /** Deleting the leader promotes the runners-up, deleting an absent word is false, and a deleted word no longer counts. */
    @Test
    void deletePromotesAndMissingDeleteIsFalse() {
        TrieAutocomplete t = example();
        assertTrue(t.remove("ape"));
        assertFalse(t.remove("ape"));
        assertFalse(t.remove("nope"));
        assertEquals(0, t.count("ape"));
        assertEquals(List.of("apple:5", "app:3"), show(t.suggest("ap", 2)));
        assertEquals(4, t.size());
    }

    /** Removing every word leaves an empty index with no stale suggestions and no leftover nodes. */
    @Test
    void removingEverythingEmptiesTheTrie() {
        TrieAutocomplete t = example();
        for (String w : List.of("apple", "apply", "app", "ape", "bat")) {
            assertTrue(t.remove(w));
        }
        assertEquals(0, t.size());
        assertEquals(List.of(), t.suggest("", 5));
        assertEquals(1, t.nodeCount());
        assertEquals(0, t.cacheSlots());
    }

    /** A word that is a prefix of another keeps its own count when the longer word is removed, and vice versa. */
    @Test
    void wordInsideWord() {
        TrieAutocomplete t = example();
        t.remove("apple");
        assertEquals(3, t.count("app"));
        t.remove("app");
        assertEquals(3, t.count("apply"));
    }

    /** Bad input throws IllegalArgumentException and changes nothing. */
    @Test
    void validation() {
        TrieAutocomplete t = example();
        assertThrows(IllegalArgumentException.class, () -> t.add(null, 1));
        assertThrows(IllegalArgumentException.class, () -> t.add("", 1));
        assertThrows(IllegalArgumentException.class, () -> t.add("x", 0));
        assertThrows(IllegalArgumentException.class, () -> t.add("x", -1));
        assertThrows(IllegalArgumentException.class, () -> t.add("bat", -2));
        assertThrows(IllegalArgumentException.class, () -> t.add("bat", Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> t.suggest(null, 1));
        assertThrows(IllegalArgumentException.class, () -> t.suggest("a", -1));
        assertThrows(IllegalArgumentException.class, () -> t.count(""));
        assertThrows(IllegalArgumentException.class, () -> t.remove(null));
        assertThrows(IllegalArgumentException.class, () -> new TrieAutocomplete(0));
        assertEquals(1, t.count("bat"));
        assertEquals(0, t.count("x"));
        assertEquals(5, t.size());
        assertEquals(1, t.suggest("b", 1).size());
    }

    /** Any character is allowed, matching is case-sensitive, and nothing is case-folded or normalised. */
    @Test
    void anyCharacterCaseSensitive() {
        TrieAutocomplete t = new TrieAutocomplete(3);
        t.add("Apple", 1);
        t.add("apple", 2);
        t.add("café", 3);
        t.add("日本", 4);
        t.add("a b-c", 1);
        assertEquals(List.of("apple:2"), show(t.suggest("a", 1).subList(0, 1)));
        assertEquals(List.of("Apple:1"), show(t.suggest("A", 3)));
        assertEquals(List.of("café:3"), show(t.suggest("caf", 3)));
        assertEquals(List.of("日本:4"), show(t.suggest("日", 3)));
        assertEquals(List.of(), t.suggest("CAF", 3));
    }

    /** A 100,000-letter word works: nothing here recurses per letter. */
    @Test
    void veryLongWordDoesNotOverflowTheStack() {
        TrieAutocomplete t = new TrieAutocomplete(2);
        String w = "a".repeat(100_000);
        t.add(w, 3);
        assertEquals(1, t.suggest("a", 5).size());
        assertEquals(3, t.count(w));
        assertTrue(t.remove(w));
        assertEquals(1, t.nodeCount());
    }

    /** Results are copies: changing a returned list never changes the index. */
    @Test
    void resultsAreCopies() {
        TrieAutocomplete t = example();
        t.suggest("ap", 2).clear();
        assertEquals(2, t.suggest("ap", 2).size());
    }

    /** An interior prefix that is not a word ("ap") is neither removable nor counted. */
    @Test
    void prefixThatIsNotAWordIsNotThere() {
        TrieAutocomplete t = example();
        assertFalse(t.remove("ap"));
        assertEquals(0, t.count("ap"));
        assertEquals(5, t.size());
        assertEquals(List.of("ape:9", "apple:5"), show(t.suggest("ap", 2)));
    }

    /** Lowering "apply" by 1 rebuilds exactly the lists that held it (the nodes apply, appl, app are checked, but only those whose list contained apply are rebuilt). */
    @Test
    void repairTouchesOnlyListsThatHeldTheWord() {
        TrieAutocomplete t = example();
        assertEquals(0, t.repairs());
        t.add("apply", -1);
        assertEquals(2, t.repairs());
        assertEquals(2, t.count("apply"));
    }
}
