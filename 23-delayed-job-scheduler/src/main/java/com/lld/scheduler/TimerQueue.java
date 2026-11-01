package com.lld.scheduler;

/**
 * The storage behind the scheduler: holds jobs until they are due. Two implementations exist ({@link HeapTimerQueue}, {@link TimingWheel}) and the differential test proves they fire the same jobs in the same order.
 *
 * <p>Not thread-safe: the scheduler calls it under its own lock.
 */
interface TimerQueue {

    /** Stores a job whose {@code due} and {@code seq} are already set. */
    void add(ScheduledJob job);

    /** Tells the queue the job was cancelled. The queue may remove it now or just leave it to be skipped later (lazy deletion). */
    void remove(ScheduledJob job);

    /** Takes out and returns the next job whose due time is at or before {@code now}, or null if none is. The job may already be cancelled; the scheduler checks. */
    ScheduledJob pollDue(long now);
}
