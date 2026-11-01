package com.lld.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BUG: a thread-safe map that holds NOT-thread-safe lists.
 * <p>Example: two threads tag "hot" at the same moment. {@code computeIfAbsent} hands both the same {@link ArrayList}, then each calls {@code add} after the map's lock is already released. Both write to the same slot, so one item is lost, or a slot is left {@code null}, or an {@code ArrayIndexOutOfBoundsException} is thrown.
 * <p>You can see the null slot as a NullPointerException from {@code Set.copyOf}. A read that happens during the writes can also throw {@code ConcurrentModificationException}.
 */
public final class ArrayListTagIndex implements TagIndex {

    private final ConcurrentHashMap<String, List<String>> index = new ConcurrentHashMap<>();

    /**
     * Adds the item to the tag's list. The broken part is that {@code add} runs outside the map's lock, so concurrent adds to the same list collide.
     */
    @Override
    public void tag(String tag, String item) {
        index.computeIfAbsent(tag, t -> new ArrayList<>()).add(item); // add() is outside the lock
    }

    /**
     * Copies the tag's list into a set. Can throw if a writer is changing the list at the same moment.
     */
    @Override
    public Set<String> itemsTagged(String tag) {
        return Set.copyOf(index.getOrDefault(tag, List.of()));
    }
}
