package com.lld.scheduler;

/** Where a job is in its life. A job is in exactly one status at any moment, and the status changes only by compare-and-set, which is what makes cancel-versus-fire a clean either/or. */
public enum JobStatus {
    /** Waiting in the queue for its due time. */
    SCHEDULED,
    /** Claimed by the scheduler; the work is running (or about to). */
    RUNNING,
    /** Finished for good: a one-shot that ran, or a job that failed for the last time. */
    DONE,
    /** Cancelled before its next run. A cancelled job never runs again. */
    CANCELLED
}
