package com.lld.hashring;

/**
 * Decides which node owns a key, and lets nodes come and go. Two implementations sit behind it: {@link ConsistentHashRing} and {@link RendezvousLocator}.
 *
 * <p>Error contract: lookups on an empty locator throw {@link NoNodesException}; a weight below 1 throws {@link IllegalArgumentException}. Membership changes are safe to call from any thread, and each one is atomic: readers see the whole old membership or the whole new one, never a mix.
 */
public interface Locator extends Lookup {

    /** Adds the node with the given weight (its share of the keys is proportional to weight). If the node already exists its weight is replaced. */
    void addNode(String node, int weight);

    /** Adds the node with weight 1. */
    default void addNode(String node) {
        addNode(node, 1);
    }

    /** Removes the node. Returns true if it was present, false if there was nothing to remove. */
    boolean removeNode(String node);

    /** Returns the current immutable snapshot. Keep it to get stable answers across a membership change. */
    Lookup snapshot();
}
