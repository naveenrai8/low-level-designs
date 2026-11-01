package com.lld.blockingqueue;

/**
 * Decides what to do with a task the pool cannot accept: the queue is full, or the pool has been shut down.
 *
 * <p>It runs on the thread that called {@code execute}. It may throw (abort), run the task itself (caller-runs), or drop something. Ready-made policies are in {@link RejectionPolicies}.
 */
@FunctionalInterface
public interface RejectionPolicy {

    /** Handles one refused task. {@code pool} lets a policy look at the pool state or drop its oldest queued task. */
    void rejected(Runnable task, FixedThreadPool pool);
}
