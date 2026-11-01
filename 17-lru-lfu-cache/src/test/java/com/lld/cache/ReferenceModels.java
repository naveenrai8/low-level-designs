package com.lld.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deliberately naive O(n) models of the two policies, used as oracles. They encode the same semantics the cache documents, and nothing clever: put on an existing key is an access, eviction happens before insert, LFU ties go to the least recent.
 */
final class ReferenceModels {

    /** Not instantiable: this class only groups the models. */
    private ReferenceModels() {
    }

    /** The operations the cache and its naive oracle must agree on. */
    interface Model {
        /** Returns the value or null, and counts as an access. */
        Integer get(int key);

        /** Inserts or overwrites, evicting first when full. */
        void put(int key, int value);

        /** Removes the key and returns its value, or null. */
        Integer remove(int key);

        /** The keys currently stored. */
        Set<Integer> keys();
    }

    /** Recency as a list, least recent first. */
    static final class Lru implements Model {
        private final int capacity;
        private final List<Integer> order = new ArrayList<>();
        private final Map<Integer, Integer> values = new HashMap<>();

        /** Creates an empty LRU model. */
        Lru(int capacity) {
            this.capacity = capacity;
        }

        /** Returns the value and moves the key to most recent, or null if absent. */
        @Override
        public Integer get(int key) {
            if (!values.containsKey(key)) {
                return null;
            }
            touch(key);
            return values.get(key);
        }

        /** Overwrites and refreshes an existing key, or evicts the least recent when full and then appends. */
        @Override
        public void put(int key, int value) {
            if (values.containsKey(key)) {
                touch(key);
            } else {
                if (values.size() == capacity) {
                    values.remove(order.remove(0));
                }
                order.add(key);
            }
            values.put(key, value);
        }

        /** Removes the key from the order and the values. */
        @Override
        public Integer remove(int key) {
            order.remove(Integer.valueOf(key));
            return values.remove(key);
        }

        /** A copy of the stored keys. */
        @Override
        public Set<Integer> keys() {
            return new HashSet<>(values.keySet());
        }

        /** Moves the key to the most-recent end. */
        private void touch(int key) {
            order.remove(Integer.valueOf(key));
            order.add(key);
        }
    }

    /** Each key carries (count, time of last use). Victim = smallest count, then oldest use. Linear scan. */
    static final class Lfu implements Model {
        /** The stored value, its access count and the logical time of its last use. */
        private record Meta(int value, long count, long lastUse) {
        }

        private final int capacity;
        private final Map<Integer, Meta> entries = new HashMap<>();
        private long clock;

        /** Creates an empty LFU model. */
        Lfu(int capacity) {
            this.capacity = capacity;
        }

        /** Returns the value and raises its count, or null if absent. */
        @Override
        public Integer get(int key) {
            Meta m = entries.get(key);
            if (m == null) {
                return null;
            }
            entries.put(key, new Meta(m.value(), m.count() + 1, ++clock));
            return m.value();
        }

        /** Overwrites an existing key (counts as an access), or evicts the lowest count (oldest use on a tie) when full, then inserts at count 1. */
        @Override
        public void put(int key, int value) {
            Meta m = entries.get(key);
            if (m != null) {
                entries.put(key, new Meta(value, m.count() + 1, ++clock));
                return;
            }
            if (entries.size() == capacity) {
                Integer victim = null;
                Meta best = null;
                for (Map.Entry<Integer, Meta> e : entries.entrySet()) {
                    Meta c = e.getValue();
                    if (best == null || c.count() < best.count()
                            || (c.count() == best.count() && c.lastUse() < best.lastUse())) {
                        best = c;
                        victim = e.getKey();
                    }
                }
                entries.remove(victim);
            }
            entries.put(key, new Meta(value, 1, ++clock));
        }

        /** Removes the key and returns its value, or null. */
        @Override
        public Integer remove(int key) {
            Meta m = entries.remove(key);
            return m == null ? null : m.value();
        }

        /** A copy of the stored keys. */
        @Override
        public Set<Integer> keys() {
            return new HashSet<>(entries.keySet());
        }
    }
}
