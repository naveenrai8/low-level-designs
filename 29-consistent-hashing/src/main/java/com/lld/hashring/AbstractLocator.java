package com.lld.hashring;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared copy-on-write machinery: the membership and the lookup table built from it are published together as one immutable object.
 *
 * <p>A change copies the member map, builds a whole new table, and swaps it in with one compare-and-set. Readers just read the reference, so they never take a lock and never see a half-built table. If two writers race, the loser rebuilds from the winner's state, so no change is lost.
 */
abstract class AbstractLocator implements Locator {

    /** One published state: the members and the table built from exactly those members. */
    private record State(SortedMap<String, Integer> members, Lookup table) {
    }

    private final AtomicReference<State> state;

    private volatile Runnable beforePublish = () -> { };

    /** Starts with an empty membership. */
    AbstractLocator() {
        this.state = new AtomicReference<>(new State(Collections.emptySortedMap(), null));
    }

    /** Builds the lookup table for a membership (name to weight, sorted by name). Called once per change, never by readers. */
    abstract Lookup build(SortedMap<String, Integer> members);

    /** Test hook: runs after a new table is built but before it is published, so a test can freeze a writer at the exact moment readers still see the old state. */
    void setBeforePublish(Runnable hook) {
        this.beforePublish = hook;
    }

    /** Validates the arguments, then adds or re-weights the node and publishes a new snapshot. Does nothing if the node is already there with the same weight. */
    @Override
    public final void addNode(String node, int weight) {
        if (node == null || node.isEmpty()) {
            throw new IllegalArgumentException("node name must not be empty");
        }
        if (weight < 1) {
            throw new IllegalArgumentException("weight must be at least 1");
        }
        mutate(m -> Integer.valueOf(weight).equals(m.put(node, weight)) ? Boolean.FALSE : Boolean.TRUE);
    }

    /** Removes the node and publishes a new snapshot. Returns false and publishes nothing if the node was not a member. */
    @Override
    public final boolean removeNode(String node) {
        if (node == null) {
            throw new IllegalArgumentException("node name must not be null");
        }
        return mutate(m -> m.remove(node) != null);
    }

    /** Applies a change to a private copy of the members and publishes the result. Returns whether anything changed. */
    private boolean mutate(java.util.function.Predicate<TreeMap<String, Integer>> change) {
        while (true) {
            State current = state.get();
            TreeMap<String, Integer> copy = new TreeMap<>(current.members());
            if (!change.test(copy)) {
                return false;
            }
            SortedMap<String, Integer> frozen = Collections.unmodifiableSortedMap(copy);
            State next = new State(frozen, build(frozen));
            beforePublish.run();
            if (state.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    /** Returns the current immutable lookup table without taking any lock. */
    @Override
    public final Lookup snapshot() {
        State s = state.get();
        return s.table() == null ? build(s.members()) : s.table();
    }

    /** Finds the owner of the key in the current snapshot. */
    @Override
    public final String locate(String key) {
        return snapshot().locate(key);
    }

    /** Finds the preference list for the key in the current snapshot. */
    @Override
    public final List<String> locate(String key, int count) {
        return snapshot().locate(key, count);
    }

    /** Returns a read-only view of the current member names. */
    @Override
    public final Set<String> nodes() {
        return Collections.unmodifiableSet(state.get().members().keySet());
    }
}
