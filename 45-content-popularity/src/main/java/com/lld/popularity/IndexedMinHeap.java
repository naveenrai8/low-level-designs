package com.lld.popularity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A min-heap of at most {@code capacity} (id, count) pairs that keeps the heaviest items, with a side index so an id already inside is updated in place and never duplicated.
 *
 * <p>The root is the weakest kept item: lowest count, and for equal counts the larger id (the same order as {@link Ranked#ORDER}, reversed). A newcomer only gets in by beating the root.
 */
final class IndexedMinHeap {

    private final int capacity;
    private final List<String> ids = new ArrayList<>();
    private final List<Long> counts = new ArrayList<>();
    private final Map<String, Integer> pos = new HashMap<>();

    /** Creates an empty heap that keeps at most {@code capacity} items. */
    IndexedMinHeap(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
    }

    /** Returns how many items are held. */
    int size() {
        return ids.size();
    }

    /** Returns the ids held, in no particular order. */
    List<String> idList() {
        return new ArrayList<>(ids);
    }

    /** Returns the weakest kept id, or null when empty. */
    String weakest() {
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Offers an item with its newest count: updates it if present, inserts it if there is room, otherwise replaces the weakest if it beats it. */
    void offer(String id, long count) {
        Integer at = pos.get(id);
        if (at != null) {
            counts.set(at, count);
            siftDown(siftUp(at));
        } else if (ids.size() < capacity) {
            ids.add(id);
            counts.add(count);
            pos.put(id, ids.size() - 1);
            siftUp(ids.size() - 1);
        } else if (weaker(ids.get(0), counts.get(0), id, count)) {
            pos.remove(ids.get(0));
            ids.set(0, id);
            counts.set(0, count);
            pos.put(id, 0);
            siftDown(0);
        }
    }

    /** Empties the heap. */
    void clear() {
        ids.clear();
        counts.clear();
        pos.clear();
    }

    /** True when (idA, cA) is weaker than (idB, cB): lower count, or equal count and larger id. */
    private static boolean weaker(String idA, long cA, String idB, long cB) {
        return cA < cB || (cA == cB && idA.compareTo(idB) > 0);
    }

    /** Moves the entry at {@code i} up while it is weaker than its parent. Returns where it ended. */
    private int siftUp(int i) {
        while (i > 0) {
            int p = (i - 1) / 2;
            if (!weaker(ids.get(i), counts.get(i), ids.get(p), counts.get(p))) {
                break;
            }
            swap(i, p);
            i = p;
        }
        return i;
    }

    /** Moves the entry at {@code i} down while a child is weaker than it. */
    private void siftDown(int i) {
        int n = ids.size();
        while (true) {
            int l = 2 * i + 1;
            int r = l + 1;
            int m = i;
            if (l < n && weaker(ids.get(l), counts.get(l), ids.get(m), counts.get(m))) {
                m = l;
            }
            if (r < n && weaker(ids.get(r), counts.get(r), ids.get(m), counts.get(m))) {
                m = r;
            }
            if (m == i) {
                return;
            }
            swap(i, m);
            i = m;
        }
    }

    /** Swaps two entries and keeps the index in step. */
    private void swap(int a, int b) {
        String ia = ids.get(a);
        String ib = ids.get(b);
        long ca = counts.get(a);
        long cb = counts.get(b);
        ids.set(a, ib);
        counts.set(a, cb);
        ids.set(b, ia);
        counts.set(b, ca);
        pos.put(ib, a);
        pos.put(ia, b);
    }
}
