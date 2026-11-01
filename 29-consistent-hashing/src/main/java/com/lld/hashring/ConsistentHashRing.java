package com.lld.hashring;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;

/**
 * A consistent-hash ring with virtual nodes.
 *
 * <p>Each node is put on a circle of 2^64 positions many times (its virtual nodes, "vnodes"). A key goes to the first vnode at or after the key's position, going clockwise, and wraps from the end back to the start. A node of weight w gets {@code w * vnodesPerWeight} vnodes, so its share of the keys follows its weight.
 *
 * <p>Positions are signed longs compared with {@link Long#compare}, so the circle runs from {@code Long.MIN_VALUE} to {@code Long.MAX_VALUE}. Lookup is a binary search: O(log P) for P points in total.
 *
 * <p>Size limit: the total number of points is capped (default {@value #DEFAULT_MAX_POINTS}); an add that would pass the cap is refused with IllegalArgumentException and publishes nothing.
 *
 * <p>Collision rule: if two vnodes land on the same position, the one whose node name sorts first (then the lower vnode number) comes first in the ring and wins the lookup. Both stay in the ring, so removing one never moves keys that the other owns.
 */
public final class ConsistentHashRing extends AbstractLocator {

    /** The most points one ring may hold (10 million, roughly 160 MB of arrays); a bigger membership is refused. */
    public static final long DEFAULT_MAX_POINTS = 10_000_000L;

    private final int vnodesPerWeight;
    private final PointHasher hasher;
    private final long maxPoints;

    /** Creates an empty ring using seeded Murmur positions. */
    public ConsistentHashRing(int vnodesPerWeight, long seed) {
        this(vnodesPerWeight, PointHasher.murmur(seed));
    }

    /** Creates an empty ring with a custom hasher (tests use this to force collisions). Throws if {@code vnodesPerWeight} is below 1. */
    public ConsistentHashRing(int vnodesPerWeight, PointHasher hasher) {
        this(vnodesPerWeight, hasher, DEFAULT_MAX_POINTS);
    }

    /** Creates an empty ring with a custom hasher and a custom cap on the total number of points. Throws if {@code vnodesPerWeight} or {@code maxPoints} is below 1, and NullPointerException if the hasher is null. */
    public ConsistentHashRing(int vnodesPerWeight, PointHasher hasher, long maxPoints) {
        if (vnodesPerWeight < 1 || maxPoints < 1) {
            throw new IllegalArgumentException("vnodesPerWeight and maxPoints must be at least 1");
        }
        this.vnodesPerWeight = vnodesPerWeight;
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.maxPoints = maxPoints;
    }

    /** Returns the text hashed for the i-th vnode of a node. Public so tests can recompute the points independently. */
    public static String vnodeLabel(String node, int i) {
        return node + "#" + i;
    }

    /** Hashes every vnode of every member and sorts the points into one array, using the collision rule in the class comment. Throws IllegalArgumentException if the total number of points would exceed the cap. */
    @Override
    Lookup build(SortedMap<String, Integer> members) {
        /** One point on the ring: its position, owning node and vnode number. */
        record Point(long hash, String node, int index) {
        }
        // Count the points first, with overflow-checked arithmetic, so a huge weight is refused before any memory is spent.
        long total = 0;
        for (int w : members.values()) {
            total += Math.multiplyExact((long) w, (long) vnodesPerWeight);
            if (total > maxPoints) {
                throw new IllegalArgumentException("ring would need more than " + maxPoints + " points");
            }
        }
        List<Point> points = new ArrayList<>((int) total);
        for (Map.Entry<String, Integer> e : members.entrySet()) {
            int count = (int) ((long) e.getValue() * vnodesPerWeight);
            for (int i = 0; i < count; i++) {
                points.add(new Point(hasher.hash(vnodeLabel(e.getKey(), i)), e.getKey(), i));
            }
        }
        points.sort(Comparator.comparingLong(Point::hash).thenComparing(Point::node).thenComparingInt(Point::index));
        long[] hashes = new long[points.size()];
        String[] owners = new String[points.size()];
        for (int i = 0; i < hashes.length; i++) {
            hashes[i] = points.get(i).hash();
            owners[i] = points.get(i).node();
        }
        return new Table(hashes, owners, Collections.unmodifiableSet(new LinkedHashSet<>(members.keySet())), hasher);
    }

    /** The immutable sorted point array. Built once, only read afterwards. */
    private static final class Table implements Lookup {
        private final long[] hashes;
        private final String[] owners;
        private final Set<String> nodes;
        private final PointHasher hasher;

        /** Keeps the parallel arrays (sorted by position then name) and the member names. */
        Table(long[] hashes, String[] owners, Set<String> nodes, PointHasher hasher) {
            this.hashes = hashes;
            this.owners = owners;
            this.nodes = nodes;
            this.hasher = hasher;
        }

        /** Returns the index of the first point whose position is at or after h, or 0 when h is past the last point (the wrap-around). */
        private int start(long h) {
            int lo = 0;
            int hi = hashes.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (Long.compare(hashes[mid], h) < 0) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo == hashes.length ? 0 : lo;
        }

        /** Finds the first point at or after the key (wrapping to the start) and returns its owner. */
        @Override
        public String locate(String key) {
            if (hashes.length == 0) {
                throw new NoNodesException();
            }
            return owners[start(hasher.hash(key))];
        }

        /** Walks clockwise from the key, collecting distinct nodes and skipping extra vnodes of nodes already chosen. */
        @Override
        public List<String> locate(String key, int count) {
            if (count < 1) {
                throw new IllegalArgumentException("count must be at least 1");
            }
            if (hashes.length == 0) {
                throw new NoNodesException();
            }
            int want = Math.min(count, nodes.size());
            Set<String> found = new LinkedHashSet<>();
            int i = start(hasher.hash(key));
            // Walk clockwise, skipping vnodes of nodes already chosen, until enough distinct nodes are found.
            for (int step = 0; step < hashes.length && found.size() < want; step++) {
                found.add(owners[(i + step) % hashes.length]);
            }
            return new ArrayList<>(found);
        }

        /** Returns the member names of this snapshot. */
        @Override
        public Set<String> nodes() {
            return nodes;
        }
    }
}
