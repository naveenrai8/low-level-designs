package com.lld.dispatch;

/** The "later" seam: virtual or real time plus a way to run a task after a delay. The dispatcher never sleeps and never reads a wall clock. */
public interface Scheduler {

    /** A handle to cancel a scheduled task. */
    interface Cancellable {
        /** Stops the task from running if it has not run yet; harmless otherwise. */
        void cancel();
    }

    /** Returns the current time in milliseconds on this scheduler's clock. */
    long nowMs();

    /**
     * Runs a task once after a delay.
     *
     * @param delayMs how long to wait, at least 0
     * @param task what to run
     * @return a handle to cancel it
     */
    Cancellable schedule(long delayMs, Runnable task);
}
