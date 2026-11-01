package com.lld.idgen;

/**
 * Where a process gets its node number from (F3).
 *
 * <p>Two live generators with the same node number can issue the same ID, so handing out node numbers safely is the real distributed problem. This seam keeps that decision out of the generator.
 */
public interface NodeIdAssigner {

    /**
     * Gives this process a node number between 0 and {@link SnowflakeLayout#MAX_NODE}, unique among all live holders. The {@code owner} must be a unique instance token, not a hostname.
     *
     * @throws IllegalStateException if every number is taken
     */
    int acquire(String owner);

    /** Gives the number back so another process can use it. Releasing a number the owner does not hold does nothing. */
    void release(String owner);
}
