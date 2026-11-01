package com.lld.cache;

import java.util.ArrayList;
import java.util.List;

/** Collects removal events in order. Single-threaded tests only. */
final class RecordingListener<K, V> implements RemovalListener<K, V> {

    final List<Removal<K, V>> events = new ArrayList<>();

    /** Records the event in arrival order. */
    @Override
    public void onRemoval(K key, V value, RemovalCause cause) {
        events.add(new Removal<>(key, value, cause));
    }

    /** Returns the keys of the recorded events with the given cause, in order. */
    List<K> keys(RemovalCause cause) {
        return events.stream().filter(r -> r.cause() == cause).map(Removal::key).toList();
    }
}
