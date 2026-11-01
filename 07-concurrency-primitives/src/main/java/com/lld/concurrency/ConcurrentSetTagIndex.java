package com.lld.concurrency;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FIX: make the value itself thread-safe.
 * <p>{@code ConcurrentHashMap.newKeySet()} is a thread-safe set, so {@code computeIfAbsent(...).add(...)} is safe. The map step and the set step are each atomic, and no rule links the two.
 * <p>If a rule did link them (for example "at most 100 items per tag"), this would be check-then-act again. Then do the whole update inside {@code compute}, which holds the key's lock.
 * <p>{@code CopyOnWriteArrayList} would also be thread-safe here, but every add copies the whole list. That is O(n) per write: fine for a handful of listeners, wrong for an index that keeps growing.
 */
public final class ConcurrentSetTagIndex implements TagIndex {

    private final ConcurrentHashMap<String, Set<String>> index = new ConcurrentHashMap<>();

    /**
     * Adds the item to the tag's concurrent set, creating the set on first use. Safe from many threads.
     */
    @Override
    public void tag(String tag, String item) {
        index.computeIfAbsent(tag, t -> ConcurrentHashMap.newKeySet()).add(item);
    }

    /**
     * Returns a frozen copy of the tag's items. Reading while others write never throws, because the set's iteration is weakly consistent.
     */
    @Override
    public Set<String> itemsTagged(String tag) {
        return Set.copyOf(index.getOrDefault(tag, Set.of())); // weakly consistent iteration, never CME
    }
}
