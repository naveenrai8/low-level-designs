package com.lld.scheduler;

import java.time.Duration;
import java.util.List;

/**
 * Runs jobs later. The caller drives it: nothing runs until {@link #runDue()} is called, and "now" comes from the injected {@link Ticker}.
 *
 * <p>Error contract: a null job, a negative delay or a non-positive period throws {@link IllegalArgumentException} or {@link NullPointerException} at scheduling time. A job that throws never throws out of the scheduler; the failure goes to {@link #deadLetters()}. A delay, period or time too large to represent is clamped to "very far away" and never throws or wraps around.
 *
 * <p>Ordering contract: jobs run in order of due time, and jobs due at the same instant run in the order they were first scheduled (a repeating job keeps its place).
 */
public interface JobScheduler {

    /** Runs {@code job} once, {@code delay} from now. A zero delay means "due right now", so the next {@link #runDue()} runs it. */
    JobHandle schedule(Job job, Duration delay);

    /** Runs {@code job} once when the ticker reaches {@code dueNanos}. A time already in the past means "due right now". */
    JobHandle scheduleAt(Job job, long dueNanos);

    /**
     * Runs {@code job} at {@code now + initialDelay}, then at start + period, start + 2 x period and so on (a fixed grid), using {@link MissedRunPolicy#CATCH_UP} for runs that were missed.
     *
     * <p>A run that throws is recorded in the dead-letter list and the job keeps going.
     */
    JobHandle scheduleAtFixedRate(Job job, Duration initialDelay, Duration period);

    /** Like {@link #scheduleAtFixedRate(Job, Duration, Duration)} but with an explicit policy for missed runs. */
    JobHandle scheduleAtFixedRate(Job job, Duration initialDelay, Duration period, MissedRunPolicy missed);

    /** Runs {@code job} at {@code now + initialDelay}, then {@code delay} after each run <em>finishes</em>, so a slow run pushes every later run back. */
    JobHandle scheduleWithFixedDelay(Job job, Duration initialDelay, Duration delay);

    /** Runs {@code job} once after {@code delay}; if it throws, tries again after the policy's backoff, until it succeeds or the attempts run out and it lands in the dead-letter list. */
    JobHandle scheduleWithRetry(Job job, Duration delay, RetryPolicy policy);

    /** Runs every job that is due at the ticker's current reading, in order, and returns how many it started. Jobs that become due while this runs (a zero-delay job scheduled by a job) are included. */
    int runDue();

    /** Returns the number of jobs still waiting for a future run (cancelled jobs are not counted). */
    int pending();

    /** Returns a snapshot of recorded failures, oldest first: a one-shot that used up its attempts, or any single failed run of a repeating job (which keeps running). Only the newest 1,000 are kept, so a repeating job that fails every run cannot grow memory forever. */
    List<FailedJob> deadLetters();
}
