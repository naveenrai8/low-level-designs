package com.lld.autocomplete;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A trie (a tree where each step down is one letter) in which every node also remembers the best K words below it.
 *
 * <p>Invariant: for every node, {@code top} is exactly the best {@code min(K, words below)} words in that node's subtree, in {@link Suggestion#ORDER}. So {@code suggest(prefix, k)} is: walk down the prefix (L steps), copy the list. No search of the subtree.
 *
 * <p>Raising a count can only help that one word, so each node on the path is fixed by "remove the old entry, insert the new one in place, drop the last if too long": O(L times K). Lowering a count or deleting is the trap: a word that was never in a node's list may now deserve a place, and the list alone cannot tell us. The repair rebuilds the list from the children's lists plus the node's own word. That is exact, because the best K of a subtree are always among the best K of its children. It runs only for nodes whose list held the changed word, bottom-up, O(L times branching times K log K).
 *
 * <p>Not thread-safe: wrap it in {@link ConcurrentAutocomplete} to share it.
 */
public final class TrieAutocomplete implements Autocomplete {

    /** Longest prefix {@link #suggestFuzzy} accepts. The fuzzy walk keeps three numbers per level, so this only limits the length of the query. */
    public static final int MAX_FUZZY_PREFIX = 256;

    /** Stands for "more than 1 away" in the fuzzy bands. */
    private static final int FAR = 2;

    /** One trie node. */
    private static final class Node {
        /** Child nodes by letter, created on first use so leaves stay small. */
        Map<Character, Node> children;
        /** This node's own word and count, or null if no word ends here. */
        Suggestion self;
        /** The best K words in this subtree, best first. */
        List<Suggestion> top = new ArrayList<>(2);
    }

    private final int capacity;
    private final Node root = new Node();
    private int words;
    private int repairs;
    private int scans;

    /** Creates an empty index that caches the best {@code capacity} words per node. A request for more than that still works, but scans the subtree. */
    public TrieAutocomplete(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.capacity = capacity;
    }

    /** Creates an index with the default cache size of 10. */
    public TrieAutocomplete() {
        this(10);
    }

    /** {@inheritDoc} */
    @Override
    public void add(String word, long delta) {
        checkWord(word);
        if (delta == 0) {
            throw new IllegalArgumentException("delta must not be 0");
        }
        Node found = find(word);
        long old = found == null || found.self == null ? 0 : found.self.count();
        if (delta < 0 && old + delta < 0) {
            throw new IllegalArgumentException("count would go below 0: " + word);
        }
        long now;
        try {
            now = Math.addExact(old, delta);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("count would overflow: " + word, e);
        }
        List<Node> path = new ArrayList<>(word.length() + 1);
        Node n = root;
        path.add(n);
        for (int i = 0; i < word.length(); i++) {
            if (n.children == null) {
                n.children = new HashMap<>(4);
            }
            n = n.children.computeIfAbsent(word.charAt(i), c -> new Node());
            path.add(n);
        }
        if (old == 0) {
            words++;
        }
        if (now == 0) {
            words--;
            n.self = null;
        } else {
            n.self = new Suggestion(word, now);
        }
        if (delta > 0) {
            for (Node p : path) {
                upsert(p.top, n.self);
            }
        } else {
            repair(path, word);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean remove(String word) {
        checkWord(word);
        Node n = find(word);
        if (n == null || n.self == null) {
            return false;
        }
        add(word, -n.self.count());
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public long count(String word) {
        checkWord(word);
        Node n = find(word);
        return n == null || n.self == null ? 0 : n.self.count();
    }

    /** {@inheritDoc} */
    @Override
    public List<Suggestion> suggest(String prefix, int k) {
        checkPrefix(prefix, k);
        Node n = find(prefix);
        if (n == null || k == 0) {
            return List.of();
        }
        return fromNode(n, k);
    }

    /** {@inheritDoc} */
    @Override
    public List<Suggestion> suggestFuzzy(String prefix, int k) {
        return fuzzy(prefix, k, new int[2]);
    }

    /** {@inheritDoc} */
    @Override
    public int size() {
        return words;
    }

    /** Returns how many trie nodes exist, counting the root. Used by the memory test. */
    int nodeCount() {
        int count = 0;
        Deque<Node> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Node n = stack.pop();
            count++;
            if (n.children != null) {
                n.children.values().forEach(stack::push);
            }
        }
        return count;
    }

    /** Returns how many cached list slots exist in the whole trie. Used by the memory test. */
    int cacheSlots() {
        int count = 0;
        Deque<Node> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Node n = stack.pop();
            count += n.top.size();
            if (n.children != null) {
                n.children.values().forEach(stack::push);
            }
        }
        return count;
    }

    /** Returns how many node lists were rebuilt from children after a decrease or delete. Tests use it to prove the trap path really ran. */
    int repairs() {
        return repairs;
    }

    /** Returns true if the node for this exact prefix exists and its cached list holds the word. Lets a test see whether the word was cached. */
    boolean cachedAt(String prefix, String word) {
        Node n = find(prefix);
        return n != null && n.top.stream().anyMatch(s -> s.word().equals(word));
    }

    /** Throws unless the word is non-null and non-empty. */
    private static void checkWord(String word) {
        if (word == null || word.isEmpty()) {
            throw new IllegalArgumentException("word must not be null or empty");
        }
    }

    /** Throws unless the prefix is non-null and k is not negative. */
    private static void checkPrefix(String prefix, int k) {
        if (prefix == null) {
            throw new IllegalArgumentException("prefix must not be null");
        }
        if (k < 0) {
            throw new IllegalArgumentException("k must not be negative");
        }
    }

    /** Walks down the letters and returns the node, or null if the path does not exist. */
    private Node find(String s) {
        Node n = root;
        for (int i = 0; i < s.length() && n != null; i++) {
            n = n.children == null ? null : n.children.get(s.charAt(i));
        }
        return n;
    }

    /** Puts a changed word into a cached list after its count went up: drops its old entry, inserts the new one in rank order, and cuts the list back to K. Exact because a rise can only help this one word. */
    private void upsert(List<Suggestion> list, Suggestion e) {
        list.removeIf(s -> s.word().equals(e.word()));
        int i = 0;
        while (i < list.size() && Suggestion.ORDER.compare(list.get(i), e) < 0) {
            i++;
        }
        if (i >= capacity) {
            return;
        }
        list.add(i, e);
        if (list.size() > capacity) {
            list.remove(list.size() - 1);
        }
    }

    /** Fixes the path after a count dropped or a word was deleted. Walks bottom-up, cuts nodes that became empty, and rebuilds the list of every node that held the word. */
    private void repair(List<Node> path, String word) {
        for (int i = path.size() - 1; i >= 0; i--) {
            Node n = path.get(i);
            if (i > 0 && n.self == null && n.children == null) {
                Node parent = path.get(i - 1);
                parent.children.remove(word.charAt(i - 1));
                if (parent.children.isEmpty()) {
                    parent.children = null;
                }
                continue;
            }
            if (n.top.stream().anyMatch(s -> s.word().equals(word))) {
                n.top = rebuild(n);
                repairs++;
            }
        }
    }

    /** Builds a node's list from its own word and its children's lists, best K, in rank order. */
    private List<Suggestion> rebuild(Node n) {
        List<Suggestion> all = new ArrayList<>();
        if (n.self != null) {
            all.add(n.self);
        }
        if (n.children != null) {
            for (Node c : n.children.values()) {
                all.addAll(c.top);
            }
        }
        all.sort(Suggestion.ORDER);
        return new ArrayList<>(all.subList(0, Math.min(capacity, all.size())));
    }

    /** Returns the best k words of a node: a copy of its list if k fits the cache, otherwise a scan of the subtree. */
    private List<Suggestion> fromNode(Node n, int k) {
        if (k <= capacity) {
            return new ArrayList<>(n.top.subList(0, Math.min(k, n.top.size())));
        }
        scans++;
        List<Suggestion> all = new ArrayList<>();
        collectAll(n, all);
        all.sort(Suggestion.ORDER);
        return new ArrayList<>(all.subList(0, Math.min(k, all.size())));
    }

    /** Adds every word in the subtree to {@code out}, with a stack so a very long word cannot overflow the call stack. Returns how many nodes it looked at. */
    private static int collectAll(Node start, List<Suggestion> out) {
        int looked = 0;
        Deque<Node> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            Node n = stack.pop();
            looked++;
            if (n.self != null) {
                out.add(n.self);
            }
            if (n.children != null) {
                n.children.values().forEach(stack::push);
            }
        }
        return looked;
    }

    /** The fuzzy search with counters: {@code stats[0]} gets the number of 3-cell rows computed and {@code stats[1]} the number of nodes scanned by subtree scans, so tests can bound the work. Needs a 2-slot array. */
    List<Suggestion> fuzzy(String prefix, int k, int[] stats) {
        checkPrefix(prefix, k);
        if (prefix.length() > MAX_FUZZY_PREFIX) {
            throw new IllegalArgumentException("fuzzy prefix longer than " + MAX_FUZZY_PREFIX);
        }
        List<Suggestion> result = new ArrayList<>(suggest(prefix, k));
        if (result.size() >= k) {
            return result;
        }
        List<Suggestion> fuzzyOnly = new ArrayList<>();
        // depth 0 band covers j = -1, 0, 1: distance of "" to the first j query letters is j
        int[] band = {FAR, 0, prefix.length() >= 1 ? 1 : FAR};
        walk(root, 0, band, true, prefix, k, fuzzyOnly, stats);
        fuzzyOnly.sort(Suggestion.ORDER);
        int need = k - result.size();
        result.addAll(fuzzyOnly.subList(0, Math.min(need, fuzzyOnly.size())));
        return result;
    }

    /** Reads cell (depth d, query letters j) from a 3-cell band, where band[i] is column j = d - 1 + i. Anything outside the band or the query is too far (FAR). */
    private static int cell(int[] band, int d, int j, int m) {
        int i = j - (d - 1);
        return i < 0 || i > 2 || j < 0 || j > m ? FAR : band[i];
    }

    /**
     * Visits trie nodes while keeping a band of three numbers per level instead of a whole table row.
     *
     * <p>The table cell for (letters walked d, first j query letters) is the edit distance between them. It is at least |d - j|, so any cell with |d - j| above 1 is already too far and never needs computing. That leaves three cells per level (j = d-1, d, d+1), so one step costs O(1) however long the query is. Distances are capped at {@code FAR} = 2, which is enough because only "at most 1" matters.
     *
     * <p>A node "matches" when the cell for the whole query is at most 1: the letters walked are one typo away from the whole query. Every word below it starts with those letters, so its cached list is used and the walk stops there. A node whose three cells are all above 1 can never match below it, so it is cut. The one exception is a matching node on the query's own path: its subtree holds the exact matches, which were already taken, so it must not hide the other words; its own word is added and its children are visited instead.
     */
    private void walk(Node n, int depth, int[] band, boolean onQuery, String q, int k, List<Suggestion> out, int[] stats) {
        int m = q.length();
        if (onQuery && depth == m) {
            return;
        }
        if (cell(band, depth, m, m) <= 1) {
            if (!onQuery) {
                contribute(n, k, out, stats);
                return;
            }
            if (n.self != null) {
                out.add(n.self);
            }
        }
        if (n.children == null) {
            return;
        }
        for (Map.Entry<Character, Node> e : n.children.entrySet()) {
            char c = e.getKey();
            int[] next = new int[3];
            int min = FAR;
            for (int i = 0; i < 3; i++) {
                int j = depth + i;
                int v = FAR;
                if (j >= 0 && j <= m) {
                    int up = cell(band, depth, j, m) + 1;
                    int left = i > 0 ? next[i - 1] + 1 : FAR;
                    int diag = j >= 1 ? cell(band, depth, j - 1, m) + (q.charAt(j - 1) == c ? 0 : 1) : FAR;
                    v = Math.min(FAR, Math.min(up, Math.min(left, diag)));
                }
                next[i] = v;
                min = Math.min(min, v);
            }
            stats[0]++;
            if (min <= 1) {
                walk(e.getValue(), depth + 1, next, onQuery && q.charAt(depth) == c, q, k, out, stats);
            }
        }
    }

    /** Adds a matching node's words to the fuzzy candidates: its cached list when k fits the cache, otherwise the whole subtree. */
    private void contribute(Node n, int k, List<Suggestion> out, int[] stats) {
        if (k <= capacity) {
            out.addAll(n.top);
        } else {
            scans++;
            stats[1] += collectAll(n, out);
        }
    }

    /** Returns how many times a query had to scan a subtree because k was above the cache size. Stays 0 when every k fits the cache, which is the O(L + K) fast path. */
    int scans() {
        return scans;
    }

    /** Returns an unmodifiable copy of the cached list at a prefix, for tests. */
    List<Suggestion> cachedList(String prefix) {
        Node n = find(prefix);
        return n == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(n.top));
    }
}
