package com.lld.hashring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeSet;

/**
 * Rendezvous (highest-random-weight) hashing: every node scores the key, the highest score wins.
 *
 * <p>There is no ring and no virtual nodes. The score of a (key, node) pair is a hash of both, so it does not change when other nodes come or go. Removing a node only affects the keys it won; adding a node only steals the keys it now scores highest on. That is the same minimal movement as the ring, with no state to build.
 *
 * <p>The price is O(n) per lookup (one score per node). Weights use the standard weighted form {@code weight / -ln(u)}, where u is a uniform number in (0,1) made from the hash, so a node of weight 2 wins twice as often as a node of weight 1. Ties go to the node whose name sorts first.
 */
public final class RendezvousLocator extends AbstractLocator {

    private final PointHasher hasher;

    /** Creates an empty locator using seeded Murmur hashing. */
    public RendezvousLocator(long seed) {
        this(PointHasher.murmur(seed));
    }

    /** Creates an empty locator with a custom hasher. Throws NullPointerException if the hasher is null. */
    public RendezvousLocator(PointHasher hasher) {
        this.hasher = java.util.Objects.requireNonNull(hasher, "hasher");
    }

    /** Combines a key hash and a node hash into one well-mixed 64-bit number. */
    static long mix(long keyHash, long nodeHash) {
        // The node hash is mixed first so the combine is not symmetric: a plain XOR of equal hashes is 0 and would give a key named like a node the lowest score for that node.
        return Hash64.fmix64(keyHash ^ Hash64.fmix64(nodeHash + 0x9E3779B97F4A7C15L));
    }

    /** Turns a mixed 64-bit number and a weight into a score: weight / -ln(u), with u in (0,1). Higher wins. */
    static double score(long mixed, int weight) {
        // 52 bits plus a half step is exact in a double and stays strictly inside (0,1); with 53 bits the top value rounds up to exactly 1.0 and the score becomes -Infinity.
        double u = ((mixed >>> 12) + 0.5) * 0x1.0p-52;
        return weight / -Math.log(u);
    }

    /** Precomputes each member's hash and weight; there is no ring to build. */
    @Override
    Lookup build(SortedMap<String, Integer> members) {
        String[] names = members.keySet().toArray(new String[0]);
        long[] nodeHashes = new long[names.length];
        int[] weights = new int[names.length];
        int i = 0;
        for (Map.Entry<String, Integer> e : members.entrySet()) {
            nodeHashes[i] = hasher.hash(e.getKey());
            weights[i] = e.getValue();
            i++;
        }
        return new Table(names, nodeHashes, weights, hasher);
    }

    /** The immutable list of nodes with their precomputed hashes and weights. */
    private static final class Table implements Lookup {
        private final String[] names;
        private final long[] nodeHashes;
        private final int[] weights;
        private final PointHasher hasher;

        /** Keeps the node arrays, which are sorted by name so ties break the same way every time. */
        Table(String[] names, long[] nodeHashes, int[] weights, PointHasher hasher) {
            this.names = names;
            this.nodeHashes = nodeHashes;
            this.weights = weights;
            this.hasher = hasher;
        }

        /** Computes the weighted score of the node at index i for a key hash. Higher wins. */
        private double score(long keyHash, int i) {
            return RendezvousLocator.score(mix(keyHash, nodeHashes[i]), weights[i]);
        }

        /** Scores every node for the key and returns the highest. */
        @Override
        public String locate(String key) {
            if (names.length == 0) {
                throw new NoNodesException();
            }
            long kh = hasher.hash(key);
            int best = 0;
            double bestScore = score(kh, 0);
            // Strict ">" keeps the earlier (alphabetically first) node on a tie.
            for (int i = 1; i < names.length; i++) {
                double s = score(kh, i);
                if (s > bestScore) {
                    best = i;
                    bestScore = s;
                }
            }
            return names[best];
        }

        /** Scores every node and returns the top count by score. */
        @Override
        public List<String> locate(String key, int count) {
            if (count < 1) {
                throw new IllegalArgumentException("count must be at least 1");
            }
            if (names.length == 0) {
                throw new NoNodesException();
            }
            long kh = hasher.hash(key);
            double[] scores = new double[names.length];
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < names.length; i++) {
                scores[i] = score(kh, i);
                order.add(i);
            }
            // Stable sort: equal scores keep name order.
            order.sort(Comparator.comparingDouble((Integer i) -> scores[i]).reversed());
            List<String> out = new ArrayList<>();
            for (int k = 0; k < Math.min(count, names.length); k++) {
                out.add(names[order.get(k)]);
            }
            return out;
        }

        /** Returns the member names of this snapshot in sorted order. */
        @Override
        public Set<String> nodes() {
            return Collections.unmodifiableSet(new TreeSet<>(List.of(names)));
        }
    }
}
