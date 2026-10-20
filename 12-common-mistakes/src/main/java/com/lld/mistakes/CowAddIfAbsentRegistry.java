package com.lld.mistakes;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <b>Fix (M5).</b> {@code addIfAbsent} checks and adds under the list's own lock. Every write still
 * copies the whole array (O(n)), so this is right for a small, rarely written list and wrong for
 * anything that grows. For a large set use {@code ConcurrentHashMap.newKeySet()}, where
 * {@code add} is already insert-if-absent.
 */
public final class CowAddIfAbsentRegistry implements Registry {

    private final CopyOnWriteArrayList<String> names = new CopyOnWriteArrayList<>();

    @Override
    public boolean register(String name) {
        return names.addIfAbsent(name);
    }

    @Override
    public List<String> all() {
        return List.copyOf(names);
    }
}
