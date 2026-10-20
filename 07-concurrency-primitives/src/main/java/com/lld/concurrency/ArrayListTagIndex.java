package com.lld.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BUG: a thread-safe map of NOT-thread-safe values. {@code computeIfAbsent} is atomic, but it
 * returns the list and the {@code add} runs <em>after</em> the map's lock is released. Concurrent
 * adds to one {@link ArrayList} lose elements, throw {@code ArrayIndexOutOfBoundsException}, or
 * leave {@code null} slots (two threads write the same index, both bump {@code size}) — observed
 * here as an NPE from {@code Set.copyOf}. A concurrent read can throw
 * {@code ConcurrentModificationException}.
 */
public final class ArrayListTagIndex implements TagIndex {

    private final ConcurrentHashMap<String, List<String>> index = new ConcurrentHashMap<>();

    @Override
    public void tag(String tag, String item) {
        index.computeIfAbsent(tag, t -> new ArrayList<>()).add(item); // add() is outside the lock
    }

    @Override
    public Set<String> itemsTagged(String tag) {
        return Set.copyOf(index.getOrDefault(tag, List.of()));
    }
}
