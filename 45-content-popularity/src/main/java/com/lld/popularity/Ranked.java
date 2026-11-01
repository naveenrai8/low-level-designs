package com.lld.popularity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * One row of a top-K answer: an item id and its count.
 *
 * @param id the item (a tag or a piece of content)
 * @param count how many events the item has in the window (an estimate, never too low, for the sketch version)
 */
public record Ranked(String id, long count) {

    /** The one tie-break rule used everywhere: bigger count first, and for equal counts the smaller id first, so answers never depend on map order. */
    public static final Comparator<Ranked> ORDER = Comparator.comparingLong(Ranked::count).reversed().thenComparing(Ranked::id);

    /** Turns a map of id to count into the best {@code k} rows in {@link #ORDER}. Returns an empty list when {@code k} is 0 and everything when {@code k} is bigger than the map. */
    public static List<Ranked> top(Map<String, Long> counts, int k) {
        if (k < 0) {
            throw new IllegalArgumentException("k must be >= 0");
        }
        List<Ranked> all = new ArrayList<>(counts.size());
        counts.forEach((id, c) -> all.add(new Ranked(id, c)));
        all.sort(ORDER);
        return k >= all.size() ? all : new ArrayList<>(all.subList(0, k));
    }
}
