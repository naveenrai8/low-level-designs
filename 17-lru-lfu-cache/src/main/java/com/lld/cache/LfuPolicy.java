package com.lld.cache;

import java.util.HashMap;
import java.util.Map;

/**
 * O(1) LFU: a doubly linked list of <b>frequency buckets</b> in ascending order, each bucket holding an LRU list of the keys with that access count.
 *
 * <pre>
 *   root &lt;-&gt; [f=1: d c] &lt;-&gt; [f=3: a] &lt;-&gt; [f=7: b] &lt;-&gt; (root)       circular, ascending
 *                     ^ least recent at f=1: the victim
 *   access(c): c moves to bucket f=2, created between f=1 and f=3 because it doesn't exist yet
 * </pre>
 *
 * <ul>
 *   <li>Access moves a key from bucket {@code f} to the <em>adjacent</em> bucket {@code f+1} (creating it if the neighbour isn't {@code f+1}). An empty bucket is unlinked at once.</li>
 *   <li>The victim is the least recent key of the first bucket. Ties at the minimum frequency go to the least recently used key (LeetCode 460 semantics).</li>
 *   <li>Every operation is O(1), <b>including arbitrary removal</b>, and the minimum is always {@code root.next}: nothing to keep in sync. The popular LeetCode 460 solution (a {@code minFreq} counter plus a map of frequency to keys) can't repair its counter when {@code remove} or expiry empties the minimum bucket. Under a count bound that's harmless, because the next insert resets it before any eviction. Under a weight bound it breaks eviction ({@code MinFreqCounterLfuTest}).</li>
 * </ul>
 * <p>The structure is from Shah, Mitra and Matani, "An O(1) algorithm for implementing the LFU cache eviction scheme" (2010).
 */
final class LfuPolicy<K> implements EvictionPolicy<K> {

    /** One key in a bucket's list, with a pointer back to the bucket that holds it. */
    private static final class Node<K> {
        final K key;
        Bucket<K> bucket;
        Node<K> prev;
        Node<K> next;

        /** Creates an unlinked node for the key. */
        Node(K key) {
            this.key = key;
        }
    }

    /** All keys that share one access count, kept as a circular list from most to least recent. */
    private static final class Bucket<K> {
        final long freq;
        Bucket<K> prev;
        Bucket<K> next;
        final Node<K> keys = new Node<>(null);   // sentinel: keys.next = most recent, keys.prev = least recent

        /** Creates an empty bucket for this access count. The sentinel points at itself. */
        Bucket(long freq) {
            this.freq = freq;
            keys.prev = keys;
            keys.next = keys;
        }

        /** True when no keys are in this bucket. */
        boolean isEmpty() {
            return keys.next == keys;
        }
    }

    private final Map<K, Node<K>> index = new HashMap<>();
    private final Bucket<K> root = new Bucket<>(0);   // sentinel: root.next = lowest frequency

    /** Creates an empty policy. The root sentinel points at itself. */
    LfuPolicy() {
        root.prev = root;
        root.next = root;
    }

    /** Starts tracking a new key at frequency 1, creating the f=1 bucket if it is missing. Throws if the key is already tracked. */
    @Override
    public void onInsert(K key) {
        Node<K> node = new Node<>(key);
        if (index.putIfAbsent(key, node) != null) {
            throw new IllegalStateException("already tracked: " + key);
        }
        Bucket<K> ones = root.next.freq == 1 ? root.next : insertBucketAfter(root, 1);
        attach(node, ones);
    }

    /** Moves the key up one frequency, to the next bucket (created if missing). The old bucket is dropped if it ends up empty. */
    @Override
    public void onAccess(K key) {
        Node<K> node = index.get(key);
        if (node == null) {
            throw new IllegalStateException("not tracked: " + key);
        }
        Bucket<K> from = node.bucket;
        Bucket<K> to = from.next.freq == from.freq + 1 ? from.next : insertBucketAfter(from, from.freq + 1);
        detach(node);                       // may unlink `from`; `to` stays linked after from's old neighbour
        attach(node, to);
    }

    /** Stops tracking a key from any bucket in O(1). Empty buckets are unlinked so the minimum stays at {@code root.next}. */
    @Override
    public void onRemove(K key) {
        Node<K> node = index.remove(key);
        if (node == null) {
            throw new IllegalStateException("not tracked: " + key);
        }
        detach(node);
    }

    /** Returns the least recent key in the lowest-frequency bucket, skipping {@code exclude}. */
    @Override
    public K victim(K exclude) {
        // Visits at most two nodes: only one key can be excluded.
        for (Bucket<K> bucket = root.next; bucket != root; bucket = bucket.next) {
            for (Node<K> node = bucket.keys.prev; node != bucket.keys; node = node.prev) {
                if (!node.key.equals(exclude)) {
                    return node.key;
                }
            }
        }
        return null;
    }

    /** How many keys are tracked. */
    @Override
    public int size() {
        return index.size();
    }

    /** Whether the key is tracked. */
    @Override
    public boolean contains(K key) {
        return index.containsKey(key);
    }

    /** What the O(1) victim rests on: frequencies strictly ascending from {@code root}, no empty bucket (so {@code root.next} is the true minimum), every node's back-pointer names the bucket it sits in, and the buckets hold exactly the indexed nodes. */
    @Override
    public void checkInvariants() {
        int count = 0;
        long previous = root.freq;
        for (Bucket<K> bucket = root.next; bucket != root; bucket = bucket.next) {
            if (bucket.next.prev != bucket) {
                throw new IllegalStateException("broken bucket link at f=" + bucket.freq);
            }
            if (bucket.freq <= previous) {
                throw new IllegalStateException("frequencies not ascending: " + previous + " then " + bucket.freq);
            }
            if (bucket.isEmpty()) {
                throw new IllegalStateException("empty bucket left linked: f=" + bucket.freq);
            }
            for (Node<K> node = bucket.keys.next; node != bucket.keys; node = node.next) {
                if (node.bucket != bucket || node.next.prev != node || index.get(node.key) != node) {
                    throw new IllegalStateException("bad back-pointer, link or index at " + node.key);
                }
                count++;
            }
            previous = bucket.freq;
        }
        if (count != index.size()) {
            throw new IllegalStateException("buckets hold " + count + " nodes, index " + index.size());
        }
    }

    /** Access count of a tracked key; for tests. */
    long frequency(K key) {
        Node<K> node = index.get(key);
        return node == null ? 0 : node.bucket.freq;
    }

    /** Creates a bucket for {@code freq} and links it right after {@code prev}. */
    private Bucket<K> insertBucketAfter(Bucket<K> prev, long freq) {
        Bucket<K> bucket = new Bucket<>(freq);
        bucket.prev = prev;
        bucket.next = prev.next;
        prev.next.prev = bucket;
        prev.next = bucket;
        return bucket;
    }

    /** Puts the node at the most-recent end of the bucket's list. */
    private static <K> void attach(Node<K> node, Bucket<K> bucket) {
        Node<K> head = bucket.keys;
        node.bucket = bucket;
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    /** Unlinks the node from its bucket, and unlinks the bucket too if that left it empty. */
    private static <K> void detach(Node<K> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = null;
        node.next = null;
        Bucket<K> bucket = node.bucket;
        node.bucket = null;
        if (bucket.isEmpty()) {
            bucket.prev.next = bucket.next;
            bucket.next.prev = bucket.prev;
        }
    }
}
