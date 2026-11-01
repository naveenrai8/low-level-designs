package com.lld.scheduler;

/** What a fixed-rate job does when a run is so slow that one or more later slots (start + k x period) have already passed. */
public enum MissedRunPolicy {
    /** Run every missed slot back-to-back, then get back on the grid. This is what {@code ScheduledThreadPoolExecutor} does: no run is ever lost, but a stall is followed by a burst. */
    CATCH_UP,
    /** Skip the missed slots and run once at the next slot still in the future. No burst, but some runs never happen. */
    COALESCE
}
