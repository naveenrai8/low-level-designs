package com.lld.scheduler;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** The scheduler's record of one job: what to run, when it is due, how it repeats, and its status. Also the handle returned to the caller. The fields {@code status} and {@code attempts} are atomic and may be touched from any thread. The fields {@code due}, {@code seq}, {@code tick}, {@code prev}, {@code next}, {@code inBucket} and {@code queued} belong to the queue and are only read or written while holding the scheduler's lock. */
final class ScheduledJob implements JobHandle {

    /** How the job repeats. */
    enum Mode { ONCE, FIXED_RATE, FIXED_DELAY }

    final long id;
    final Job job;
    final Mode mode;
    /** Period for the repeating modes, the gap after each run for FIXED_DELAY; zero for ONCE. */
    final long intervalNanos;
    final MissedRunPolicy missed;
    final RetryPolicy retry;
    final AtomicReference<JobStatus> status = new AtomicReference<>(JobStatus.SCHEDULED);
    final AtomicInteger attempts = new AtomicInteger();
    private final TimerScheduler owner;

    /** When the next run is due, in ticker nanoseconds. */
    long due;
    /** Tie-breaker: the order in which this entry was put in the queue. Smaller runs first among equal due times. */
    long seq;
    /** The timing wheel's due tick (due rounded up to a whole tick). */
    long tick;
    /** Timing wheel list links; {@code inBucket} is true while the entry sits in a bucket list. */
    ScheduledJob prev;
    ScheduledJob next;
    boolean inBucket;
    /** Heap bookkeeping: true while the heap holds this entry, so a cancel after the job was already polled is not counted as a tombstone. */
    boolean queued;

    /** Creates the record. {@code due} and {@code seq} are filled in when it is queued. */
    ScheduledJob(long id, Job job, Mode mode, long intervalNanos, MissedRunPolicy missed, RetryPolicy retry, TimerScheduler owner) {
        this.id = id;
        this.job = job;
        this.mode = mode;
        this.intervalNanos = intervalNanos;
        this.missed = missed;
        this.retry = retry;
        this.owner = owner;
    }

    /** Returns true if the job repeats forever until cancelled. */
    boolean periodic() {
        return mode != Mode.ONCE;
    }

    /** Returns the unique id. */
    @Override
    public long id() {
        return id;
    }

    /** Cancels through the owning scheduler, which decides the winner of a cancel-versus-fire race. */
    @Override
    public boolean cancel() {
        return owner.cancel(this);
    }

    /** Returns the current status. */
    @Override
    public JobStatus status() {
        return status.get();
    }

    /** Returns how many times the work has started. */
    @Override
    public int attempts() {
        return attempts.get();
    }
}
