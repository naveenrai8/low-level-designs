package com.lld.hashring;

/**
 * Turns a text (a key, or a virtual-node label) into a position on the ring.
 *
 * <p>It is an interface so that tests can plug in a tiny or rigged hash to force collisions and boundary cases that a real hash would almost never produce.
 */
@FunctionalInterface
public interface PointHasher {

    /** Returns the 64-bit position for the text. Must be deterministic: the same text always gives the same number. */
    long hash(String text);

    /** Returns the production hasher: seeded MurmurHash3, see {@link Hash64}. */
    static PointHasher murmur(long seed) {
        return text -> Hash64.hash(text, seed);
    }
}
