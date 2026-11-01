package com.lld.cache;

import java.util.HashMap;
import java.util.Map;

/**
 * LRU as a hash map plus a doubly linked list: the version interviewers expect you to write. {@code LinkedHashMap(accessOrder = true)} does the same thing and is covered in Topic 12 (M2).
 *
 * <pre>
 *   sentinel &lt;-&gt; most recent &lt;-&gt; ... &lt;-&gt; least recent &lt;-&gt; (sentinel)     circular
 * </pre>
 * One sentinel removes every null check: an empty list is the sentinel pointing at itself.
 */
final class LruPolicy<K> implements EvictionPolicy<K> {

    /** One key in the circular list. */
    private static final class Node<K> {
        final K key;
        Node<K> prev;
        Node<K> next;

        /** Creates an unlinked node for the key. */
        Node(K key) {
            this.key = key;
        }
    }

    private final Map<K, Node<K>> index = new HashMap<>();
    private final Node<K> sentinel = new Node<>(null);   // sentinel.next = most recent, sentinel.prev = least recent

    /** Creates an empty list. The sentinel points at itself. */
    LruPolicy() {
        sentinel.prev = sentinel;
        sentinel.next = sentinel;
    }

    /** Starts tracking a new key as the most recent. Throws if the key is already tracked. */
    @Override
    public void onInsert(K key) {
        Node<K> node = new Node<>(key);
        if (index.putIfAbsent(key, node) != null) {
            throw new IllegalStateException("already tracked: " + key);
        }
        linkFirst(node);
    }

    /** Moves the key to the most-recent end of the list. */
    @Override
    public void onAccess(K key) {
        Node<K> node = tracked(key);
        unlink(node);
        linkFirst(node);
    }

    /** Stops tracking the key and unlinks its node. Throws if it was not tracked. */
    @Override
    public void onRemove(K key) {
        Node<K> node = index.remove(key);
        if (node == null) {
            throw new IllegalStateException("not tracked: " + key);
        }
        unlink(node);
    }

    /** Returns the least recent key, or the one before it if that is {@code exclude}. Null when nothing qualifies. */
    @Override
    public K victim(K exclude) {
        Node<K> node = sentinel.prev;
        if (node != sentinel && node.key.equals(exclude)) {
            node = node.prev;                                // at most one step: only one key is excluded
        }
        return node == sentinel ? null : node.key;
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

    /** Links are symmetric, every node is the indexed one, and the list holds exactly the index. */
    @Override
    public void checkInvariants() {
        int count = 0;
        for (Node<K> node = sentinel.next; node != sentinel; node = node.next) {
            if (node.next.prev != node || index.get(node.key) != node) {
                throw new IllegalStateException("broken link or stale index at " + node.key);
            }
            count++;
        }
        if (count != index.size()) {
            throw new IllegalStateException("list " + count + " != index " + index.size());
        }
    }

    /** Finds the node for a key, or throws if it is not tracked. */
    private Node<K> tracked(K key) {
        Node<K> node = index.get(key);
        if (node == null) {
            throw new IllegalStateException("not tracked: " + key);
        }
        return node;
    }

    /** Links the node right after the sentinel, which makes it the most recent. */
    private void linkFirst(Node<K> node) {
        node.prev = sentinel;
        node.next = sentinel.next;
        sentinel.next.prev = node;
        sentinel.next = node;
    }

    /** Cuts the node out of the list and clears its pointers. */
    private static <K> void unlink(Node<K> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = null;
        node.next = null;
    }
}
