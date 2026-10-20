package com.lld.mistakes;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <b>Bug (M5).</b> The old reviews' blanket fix was "use CopyOnWriteArrayList". It makes each call
 * thread-safe, but {@code contains} then {@code add} is still two calls, so two threads both see
 * "absent" and both add. Duplicates, from a "thread-safe" list.
 */
public final class CowCheckThenActRegistry implements Registry {

    private final List<String> names = new CopyOnWriteArrayList<>();
    private final Gap gap;

    public CowCheckThenActRegistry(Gap gap) {
        this.gap = gap;
    }

    @Override
    public boolean register(String name) {
        if (names.contains(name)) {
            return false;
        }
        gap.pause();
        names.add(name);                               // BUG: act on a stale check
        return true;
    }

    @Override
    public List<String> all() {
        return List.copyOf(names);
    }
}
