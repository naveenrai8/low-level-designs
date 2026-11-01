package com.lld.mistakes;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <b>Bug (M5).</b> The old reviews' blanket fix was "use CopyOnWriteArrayList". It makes each call thread-safe, but {@code contains} then {@code add} is still two calls.
 * <p>Example: two threads register the same URL. Both see "absent", and both add. The list now holds the URL twice, from a "thread-safe" list.
 */
public final class CowCheckThenActRegistry implements Registry {

    private final List<String> names = new CopyOnWriteArrayList<>();
    private final Gap gap;

    /**
     * Creates the broken registry. The gap sits between the check and the add so a test can force the race.
     */
    public CowCheckThenActRegistry(Gap gap) {
        this.gap = gap;
    }

    /**
     * Checks {@code contains}, pauses in the gap, then adds. Two threads can both pass the check, so both add.
     */
    @Override
    public boolean register(String name) {
        if (names.contains(name)) {
            return false;
        }
        gap.pause();
        names.add(name);                               // BUG: act on a stale check
        return true;
    }

    /**
     * Returns a frozen copy of the names.
     */
    @Override
    public List<String> all() {
        return List.copyOf(names);
    }
}
