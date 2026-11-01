package com.lld.blockingqueue;

import java.util.concurrent.RejectedExecutionException;

/** The four classic rejection policies, named the same as in {@code ThreadPoolExecutor}. */
public final class RejectionPolicies {

    private static final RejectionPolicy ABORT = (task, pool) -> {
        throw new RejectedExecutionException("task rejected: queue full or pool shut down");
    };

    private static final RejectionPolicy CALLER_RUNS = (task, pool) -> {
        // there is a small gap between this check and run(): a shutdown can land in it, so one task may run just after shutdown (the JDK has the same gap)
        if (!pool.isShutdown()) {
            task.run();
        }
    };

    private static final RejectionPolicy DISCARD = (task, pool) -> { };

    private static final RejectionPolicy DISCARD_OLDEST = (task, pool) -> {
        // load-bearing guard: after shutdown execute() would call this policy again, which would call execute() again, forever (StackOverflowError)
        if (!pool.isShutdown()) {
            pool.dropOldestQueued();
            pool.execute(task);
        }
    };

    /** Not meant to be created: this class only holds static factories. */
    private RejectionPolicies() {
    }

    /** Throws {@link RejectedExecutionException} to the caller. Loud and safe: nothing is lost silently. This is the default. */
    public static RejectionPolicy abort() {
        return ABORT;
    }

    /** Runs the task on the calling thread. The caller is busy for a while, so it cannot submit more: that is backpressure. A shut-down pool drops the task instead. */
    public static RejectionPolicy callerRuns() {
        return CALLER_RUNS;
    }

    /** Silently drops the new task. Only fine for work nobody needs, such as a metrics sample. */
    public static RejectionPolicy discard() {
        return DISCARD;
    }

    /** Drops the task that has waited longest, then tries to queue the new one. Good when fresh work beats stale work. Under heavy contention the retry can drop more than one old task, and in theory the retry depth is unbounded: the JDK's version behaves the same way. A shut-down pool drops the new task. */
    public static RejectionPolicy discardOldest() {
        return DISCARD_OLDEST;
    }
}
