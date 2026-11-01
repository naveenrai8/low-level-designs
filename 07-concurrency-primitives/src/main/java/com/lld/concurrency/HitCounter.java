package com.lld.concurrency;

/**
 * Counts hits from many threads. The broken and fixed versions differ only in whether no hit is lost.
 */
public interface HitCounter {

    /**
     * Records one hit.
     */
    void hit();

    /**
     * Returns how many hits have been recorded so far.
     */
    long total();
}
