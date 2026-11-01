package com.lld.hashring;

import java.util.List;
import java.util.Set;

/**
 * A read-only view of one fixed membership: "which node owns this key".
 *
 * <p>A {@link Locator} hands out a {@code Lookup} snapshot. The snapshot never changes, so a reader that holds one gets the same answers even while nodes are added or removed behind it.
 */
public interface Lookup {

    /** Returns the one node that owns the key. Throws {@link NoNodesException} if the membership is empty. */
    String locate(String key);

    /**
     * Returns up to {@code count} different nodes for the key, best first (the "preference list", used for replication). The first entry equals {@link #locate(String)}.
     *
     * <p>Returns fewer than {@code count} when there are not enough nodes. Throws {@link NoNodesException} if empty and {@link IllegalArgumentException} if {@code count} is below 1.
     */
    List<String> locate(String key, int count);

    /** Returns the node names in this snapshot, in sorted order. */
    Set<String> nodes();
}
