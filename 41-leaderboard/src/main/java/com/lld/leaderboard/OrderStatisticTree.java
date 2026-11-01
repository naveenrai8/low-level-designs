package com.lld.leaderboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

/**
 * A sorted collection that can answer "how many items come before this one" and "what is the item at position i" in O(log n).
 *
 * <p>It is a treap: a binary search tree where every node also gets a random priority, and the tree is kept shaped like a heap on that priority. Random priorities keep the tree balanced on average (the average node depth is about 1.4 * log2 n, and the deepest node is about 4.31 * ln n). Every node stores the size of its subtree, and that size is what turns a normal tree walk into a rank query: going down, add up the sizes of the left parts you skip.
 *
 * <p>Priorities come from a seeded random generator, so the shape is the same on every run and tests are repeatable. This class is not thread safe, callers lock around it.
 *
 * @param <K> the key type, ordered by the comparator given at construction. Keys must be unique under that comparator.
 */
final class OrderStatisticTree<K> {

    /** One tree node: the key, its random priority, the number of nodes in its subtree and the two children. */
    private static final class Node<K> {
        final K key;
        final long priority;
        int size = 1;
        Node<K> left;
        Node<K> right;

        /** Creates a leaf node holding {@code key} with the given random priority. */
        Node(K key, long priority) {
            this.key = key;
            this.priority = priority;
        }
    }

    /** The two halves produced by splitting a tree around a key. */
    private record Split<K>(Node<K> left, Node<K> right) {
    }

    private final Comparator<? super K> order;
    private final SplittableRandom random;
    private Node<K> root;
    private long visits;

    /** Creates an empty tree ordered by {@code order}. The seed fixes the priorities, so the shape is repeatable. */
    OrderStatisticTree(Comparator<? super K> order, long seed) {
        this.order = order;
        this.random = new SplittableRandom(seed);
    }

    /** Returns the number of keys in the tree. */
    int size() {
        return sizeOf(root);
    }

    /** Adds a key. The key must not already be present, because the leaderboard always removes the old key before adding the new one. */
    void insert(K key) {
        Split<K> parts = split(root, key);
        Node<K> node = new Node<>(key, random.nextLong());
        root = merge(merge(parts.left(), node), parts.right());
    }

    /** Removes the key if it is present, and does nothing if it is not. */
    void remove(K key) {
        root = erase(root, key);
    }

    /** Returns how many tree nodes the read operations have touched since the last reset. Tests use it to prove a rank or top-K never scans the whole tree, which a comparison counter cannot see. */
    long visits() {
        return visits;
    }

    /** Sets the node-visit counter back to zero. */
    void resetVisits() {
        visits = 0;
    }

    /** Returns how many keys sort strictly before {@code key}. The key itself need not be in the tree, which is how "how many users have a higher score" is asked. */
    int countLess(K key) {
        int count = 0;
        Node<K> n = root;
        while (n != null) {
            visits++;
            if (order.compare(key, n.key) <= 0) {
                n = n.left;
            } else {
                count += sizeOf(n.left) + 1;
                n = n.right;
            }
        }
        return count;
    }

    /** Returns the key at 0-based position {@code index} in sorted order. Throws if the index is outside the tree. */
    K kth(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException("index " + index + " size " + size());
        }
        Node<K> n = root;
        int i = index;
        while (true) {
            visits++;
            int leftSize = sizeOf(n.left);
            if (i < leftSize) {
                n = n.left;
            } else if (i == leftSize) {
                return n.key;
            } else {
                i -= leftSize + 1;
                n = n.right;
            }
        }
    }

    /** Returns the keys at positions from (inclusive) to to (exclusive), 0-based, cut to the tree size. Costs O(log n + number of keys returned). */
    List<K> slice(int from, int to) {
        visits++;
        List<K> out = new ArrayList<>();
        collect(root, Math.max(from, 0), Math.min(to, size()), out);
        return out;
    }

    /** Walks the tree in order, skipping whole subtrees that lie outside the wanted positions. */
    private void collect(Node<K> n, int from, int to, List<K> out) {
        if (n == null || from >= to) {
            return;
        }
        visits++;
        int leftSize = sizeOf(n.left);
        if (from < leftSize) {
            collect(n.left, from, Math.min(to, leftSize), out);
        }
        if (from <= leftSize && leftSize < to) {
            out.add(n.key);
        }
        if (to > leftSize + 1) {
            collect(n.right, Math.max(from - leftSize - 1, 0), to - leftSize - 1, out);
        }
    }

    /** Returns the depth of the deepest node, used by tests to show the tree stays shallow. */
    int height() {
        return height(root);
    }

    /** Recursive helper for {@link #height()}. */
    private int height(Node<K> n) {
        return n == null ? 0 : 1 + Math.max(height(n.left), height(n.right));
    }

    /** Returns the subtree size, 0 for a missing node. */
    private static int sizeOf(Node<?> n) {
        return n == null ? 0 : n.size;
    }

    /** Recomputes a node's size from its children after they changed. */
    private static void refresh(Node<?> n) {
        n.size = sizeOf(n.left) + sizeOf(n.right) + 1;
    }

    /** Cuts the tree into keys strictly less than {@code key} (left) and the rest (right). */
    private Split<K> split(Node<K> n, K key) {
        if (n == null) {
            return new Split<>(null, null);
        }
        if (order.compare(n.key, key) < 0) {
            Split<K> parts = split(n.right, key);
            n.right = parts.left();
            refresh(n);
            return new Split<>(n, parts.right());
        }
        Split<K> parts = split(n.left, key);
        n.left = parts.right();
        refresh(n);
        return new Split<>(parts.left(), n);
    }

    /** Joins two trees where every key in {@code a} sorts before every key in {@code b}, keeping the heap order of priorities. */
    private Node<K> merge(Node<K> a, Node<K> b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (a.priority > b.priority) {
            a.right = merge(a.right, b);
            refresh(a);
            return a;
        }
        b.left = merge(a, b.left);
        refresh(b);
        return b;
    }

    /** Removes the node equal to {@code key} from the subtree by merging its two children, and returns the new subtree root. */
    private Node<K> erase(Node<K> n, K key) {
        if (n == null) {
            return null;
        }
        int c = order.compare(key, n.key);
        if (c == 0) {
            return merge(n.left, n.right);
        }
        if (c < 0) {
            n.left = erase(n.left, key);
        } else {
            n.right = erase(n.right, key);
        }
        refresh(n);
        return n;
    }
}
