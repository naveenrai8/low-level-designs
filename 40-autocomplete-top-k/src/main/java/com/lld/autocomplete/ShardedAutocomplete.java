package com.lld.autocomplete;

import java.util.ArrayList;
import java.util.List;

/**
 * The sharding seam: splits the words over several independent indexes by the first {@code routeLen} letters of the word.
 *
 * <p>Routing rule: shard = hash of the first routeLen letters (the whole word if shorter), modulo the shard count. All words with the same first routeLen letters share a shard, so a prefix of routeLen letters or more goes to exactly one shard. A shorter prefix (including the empty one) could match words in any shard, so it goes to all shards, and the answer is the best k of the shards' best k lists: exact, because the shards hold disjoint words. Fuzzy search always asks every shard, since a typo in the first letters changes the route; exact matches are merged first, then the fuzzy-only ones.
 *
 * <p>Here the shards are in-process objects. In production each would be a server, and the hash would be a consistent hash (see Topic 29).
 */
public final class ShardedAutocomplete implements Autocomplete {

    private final Autocomplete[] shards;
    private final int routeLen;

    /** Creates a router over the given shards. {@code routeLen} must be at least 1. */
    public ShardedAutocomplete(List<? extends Autocomplete> shards, int routeLen) {
        if (shards.isEmpty() || routeLen < 1) {
            throw new IllegalArgumentException("need at least one shard and routeLen >= 1");
        }
        this.shards = shards.toArray(new Autocomplete[0]);
        this.routeLen = routeLen;
    }

    /** Returns the shard that owns a word, or that owns every word with this prefix when the prefix has at least routeLen letters. */
    private Autocomplete shardFor(String s) {
        String key = s.length() > routeLen ? s.substring(0, routeLen) : s;
        return shards[Math.floorMod(key.hashCode(), shards.length)];
    }

    /** {@inheritDoc} */
    @Override
    public void add(String word, long delta) {
        shardFor(requireWord(word)).add(word, delta);
    }

    /** {@inheritDoc} */
    @Override
    public boolean remove(String word) {
        return shardFor(requireWord(word)).remove(word);
    }

    /** {@inheritDoc} */
    @Override
    public long count(String word) {
        return shardFor(requireWord(word)).count(word);
    }

    /** {@inheritDoc} */
    @Override
    public List<Suggestion> suggest(String prefix, int k) {
        if (prefix == null) {
            throw new IllegalArgumentException("prefix must not be null");
        }
        if (prefix.length() >= routeLen) {
            return shardFor(prefix).suggest(prefix, k);
        }
        List<Suggestion> all = new ArrayList<>();
        for (Autocomplete s : shards) {
            all.addAll(s.suggest(prefix, k));
        }
        return best(all, k);
    }

    /** {@inheritDoc} */
    @Override
    public List<Suggestion> suggestFuzzy(String prefix, int k) {
        if (prefix == null) {
            throw new IllegalArgumentException("prefix must not be null");
        }
        List<Suggestion> exact = new ArrayList<>();
        List<Suggestion> fuzzyOnly = new ArrayList<>();
        for (Autocomplete s : shards) {
            for (Suggestion x : s.suggestFuzzy(prefix, k)) {
                (x.word().startsWith(prefix) ? exact : fuzzyOnly).add(x);
            }
        }
        List<Suggestion> result = best(exact, k);
        result.addAll(best(fuzzyOnly, k - result.size()));
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public int size() {
        int total = 0;
        for (Autocomplete s : shards) {
            total += s.size();
        }
        return total;
    }

    /** Returns the best k of the list in rank order. */
    private static List<Suggestion> best(List<Suggestion> all, int k) {
        all.sort(Suggestion.ORDER);
        return new ArrayList<>(all.subList(0, Math.min(k, all.size())));
    }

    /** Throws unless the word is non-null and non-empty, then returns it. */
    private static String requireWord(String word) {
        if (word == null || word.isEmpty()) {
            throw new IllegalArgumentException("word must not be null or empty");
        }
        return word;
    }
}
