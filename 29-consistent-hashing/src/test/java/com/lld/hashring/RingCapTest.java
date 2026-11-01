package com.lld.hashring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** Proves the ring refuses a membership whose vnode count overflows an int or exceeds the point cap, and publishes nothing when it refuses. */
class RingCapTest {

    /** A weight so large that weight x 200 overflows int is rejected with IllegalArgumentException, not silently turned into zero points. */
    @Test
    void overflowingVnodeCountIsRejected() {
        ConsistentHashRing ring = new ConsistentHashRing(200, 1);
        assertThrows(IllegalArgumentException.class, () -> ring.addNode("big", 20_000_000));
        assertThrows(IllegalArgumentException.class, () -> ring.addNode("big", Integer.MAX_VALUE));
        assertEquals(Set.of(), ring.nodes());
    }

    /** A weight whose product wraps around to a small positive int (21,474,837 x 200 = 2^32 + 104, which an int sees as 104) must also be rejected; only exact arithmetic catches this one, because the wrapped count looks harmless. */
    @Test
    void wrapToSmallPositiveIsRejected() {
        ConsistentHashRing ring = new ConsistentHashRing(200, 1);
        assertThrows(IllegalArgumentException.class, () -> ring.addNode("sneaky", 21_474_837));
        assertEquals(Set.of(), ring.nodes());
    }

    /** The default cap is 10,000,000 points: a single node needing 10,000,001 is refused (checked before any point is built, so it is fast). */
    @Test
    void defaultCapIsTenMillion() {
        ConsistentHashRing ring = new ConsistentHashRing(1, 1);
        assertEquals(10_000_000, ConsistentHashRing.DEFAULT_MAX_POINTS);
        assertThrows(IllegalArgumentException.class, () -> ring.addNode("huge", 10_000_001));
    }

    /** With a cap of 1000 points and 10 vnodes per weight, exactly 1000 points in total is accepted and one more is refused. */
    @Test
    void capBoundaryIsExact() {
        ConsistentHashRing ring = new ConsistentHashRing(10, PointHasher.murmur(1), 1000);
        ring.addNode("a", 60);
        ring.addNode("b", 40);
        assertEquals(Set.of("a", "b"), ring.nodes());
        assertThrows(IllegalArgumentException.class, () -> ring.addNode("c", 1));
        ring.addNode("a", 59);
        ring.addNode("c", 1);
        assertEquals(Set.of("a", "b", "c"), ring.nodes());
    }

    /** A refused add leaves the published snapshot and member list exactly as they were, and lookups still work. */
    @Test
    void rejectedAddPublishesNothing() {
        ConsistentHashRing ring = new ConsistentHashRing(10, PointHasher.murmur(1), 1000);
        ring.addNode("a", 50);
        Lookup before = ring.snapshot();
        String owner = ring.locate("k");
        assertThrows(IllegalArgumentException.class, () -> ring.addNode("b", 51));
        assertThrows(IllegalArgumentException.class, () -> ring.addNode("a", 101));
        assertSame(before, ring.snapshot());
        assertEquals(Set.of("a"), ring.nodes());
        assertEquals(owner, ring.locate("k"));
    }
}
