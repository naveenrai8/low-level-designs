package com.lld.concurrency;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FIX: make the value itself concurrent. {@code ConcurrentHashMap.newKeySet()} is a thread-safe
 * set, so {@code computeIfAbsent(...).add(...)} is now safe: the map step and the set step are each
 * atomic, and no invariant spans both.
 *
 * <p>If an invariant <em>did</em> span them (e.g. "at most 100 items per tag"), this would be
 * check-then-act again; do the whole update inside {@code compute}, which holds the key's lock.
 * {@code CopyOnWriteArrayList} would also be thread-safe here, but every add copies the whole
 * list: O(n) per write, fine for a handful of listeners, wrong for an index that grows.
 */
public final class ConcurrentSetTagIndex implements TagIndex {

    private final ConcurrentHashMap<String, Set<String>> index = new ConcurrentHashMap<>();

    @Override
    public void tag(String tag, String item) {
        index.computeIfAbsent(tag, t -> ConcurrentHashMap.newKeySet()).add(item);
    }

    @Override
    public Set<String> itemsTagged(String tag) {
        return Set.copyOf(index.getOrDefault(tag, Set.of())); // weakly consistent iteration, never CME
    }
}
