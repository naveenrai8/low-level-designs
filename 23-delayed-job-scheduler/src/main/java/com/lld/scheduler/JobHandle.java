package com.lld.scheduler;

/** A receipt for a scheduled job. Keep it to cancel the job or to ask how it is doing. */
public interface JobHandle {

    /** Returns the scheduler-wide unique id of this job. Ids increase in the order jobs were scheduled. */
    long id();

    /**
     * Tries to cancel the job.
     *
     * <p>Returns {@code true} only if this call is the reason the job will not (or will no longer) run: a one-shot that had not been claimed yet, or a repeating job (even in the middle of a run: that run finishes, but no later run starts). Returns {@code false} if the job already ran, was already cancelled, or a one-shot is running right now.
     *
     * <p>Cancel racing the fire has exactly one winner: either the job runs and this returns {@code false}, or it never runs and this returns {@code true}.
     */
    boolean cancel();

    /** Returns the current status. It can be stale by the time the caller looks at it. */
    JobStatus status();

    /** Returns how many times the work has started so far, retries included. */
    int attempts();
}
