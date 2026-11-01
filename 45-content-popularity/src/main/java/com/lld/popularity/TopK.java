package com.lld.popularity;

import java.util.List;

/**
 * Counts events per item over a sliding window and answers "which items are hottest right now".
 *
 * <p>Error contract: a null or blank item is an {@link IllegalArgumentException}; a negative {@code k} is an {@link IllegalArgumentException}; {@code k = 0} and an empty window give an empty list. Ties are broken by {@link Ranked#ORDER}.
 */
public interface TopK {

    /** Counts one event for {@code item} at the current time. */
    void record(String item);

    /**
     * Counts one event that happened at {@code atNanos} on the same clock as the ticker.
     *
     * <p>Rule for late events: if the event is older than the window it is rejected and {@code false} is returned; an event inside the window is added to its own bucket. A timestamp later than the ticker's current reading (even by one nanosecond) throws {@link IllegalArgumentException}.
     */
    boolean recordAt(String item, long atNanos);

    /** Returns the best {@code k} items in the window, hottest first. Returns fewer when the window holds fewer distinct items. */
    List<Ranked> topK(int k);
}
