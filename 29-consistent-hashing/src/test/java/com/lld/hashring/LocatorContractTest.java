package com.lld.hashring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Proves the error contract and the full preference list for both implementations, the second against an independent brute-force oracle. */
class LocatorContractTest {

    /** Empty locator throws the typed error; bad arguments throw IllegalArgumentException; removing a missing node returns false and a present one true. */
    @ParameterizedTest
    @EnumSource(LocatorFactory.class)
    void errorContract(LocatorFactory f) {
        Locator l = f.create(1);
        assertThrows(NoNodesException.class, () -> l.locate("k"));
        assertThrows(NoNodesException.class, () -> l.locate("k", 2));
        assertThrows(NoNodesException.class, () -> l.snapshot().locate("k"));
        assertThrows(IllegalArgumentException.class, () -> l.addNode("a", 0));
        assertThrows(IllegalArgumentException.class, () -> l.addNode("a", -3));
        assertThrows(IllegalArgumentException.class, () -> l.addNode("", 1));
        assertThrows(IllegalArgumentException.class, () -> l.addNode(null, 1));
        assertThrows(IllegalArgumentException.class, () -> l.removeNode(null));
        l.addNode("a");
        assertThrows(IllegalArgumentException.class, () -> l.locate("k", 0));
        assertThrows(IllegalArgumentException.class, () -> l.locate("k", -1));
        assertFalse(l.removeNode("nope"));
        assertTrue(l.removeNode("a"));
        assertThrows(NoNodesException.class, () -> l.locate("k"));
    }

    /** Oracle for the ring: sort all (position, name, vnode) points, start at the first point at or after the key (wrapping), and collect distinct names until count are found. */
    private static List<String> ringOracle(Map<String, Integer> members, int vn, PointHasher h, String key, int count) {
        /** One ring point for the oracle: position, owner and vnode number. */
        record P(long hash, String node, int idx) {
        }
        List<P> pts = new ArrayList<>();
        members.forEach((n, w) -> {
            for (int i = 0; i < w * vn; i++) {
                pts.add(new P(h.hash(ConsistentHashRing.vnodeLabel(n, i)), n, i));
            }
        });
        pts.sort(Comparator.comparing((P p) -> p.hash()).thenComparing(P::node).thenComparingInt(P::idx));
        long kh = h.hash(key);
        int start = 0;
        while (start < pts.size() && Long.compare(pts.get(start).hash(), kh) < 0) {
            start++;
        }
        Set<String> out = new LinkedHashSet<>();
        for (int s = 0; s < pts.size() && out.size() < count; s++) {
            out.add(pts.get((start + s) % pts.size()).node());
        }
        return new ArrayList<>(out);
    }

    /** Oracle for rendezvous: score every node with the same published formula, sort by score descending (ties by name) and take the top count. */
    private static List<String> rendezvousOracle(Map<String, Integer> members, PointHasher h, String key, int count) {
        long kh = h.hash(key);
        List<String> names = new ArrayList<>(new java.util.TreeSet<>(members.keySet()));
        names.sort(Comparator.comparingDouble((String n) -> RendezvousLocator.score(RendezvousLocator.mix(kh, h.hash(n)), members.get(n))).reversed());
        return names.subList(0, Math.min(count, names.size()));
    }

    /** For 400 keys and every list length from 1 to 6 (one more than the node count), the whole preference list equals the oracle's, with mixed weights. A shortcut such as "best node, then the rest in name order" fails here. */
    @ParameterizedTest
    @EnumSource(LocatorFactory.class)
    void wholePreferenceListMatchesOracle(LocatorFactory f) {
        Map<String, Integer> members = Map.of("a", 1, "b", 2, "c", 1, "d", 3, "e", 1);
        Locator l = f.create(31);
        members.forEach(l::addNode);
        PointHasher h = PointHasher.murmur(31);
        for (int k = 0; k < 400; k++) {
            String key = "pref-" + k;
            for (int count = 1; count <= 6; count++) {
                List<String> want = f == LocatorFactory.RING ? ringOracle(members, 200, h, key, count) : rendezvousOracle(members, h, key, count);
                assertEquals(want, l.locate(key, count), key + " count=" + count);
            }
        }
    }
}
