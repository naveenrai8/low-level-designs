package com.lld.scheduler;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * The scheduler. It owns a {@link TimerQueue} (heap or wheel), a lock that guards the queue, and the rules for cancel, repeat and retry.
 *
 * <p>Concurrency model. The queue is touched only while holding {@code lock}. The job body runs outside the lock, so a slow job never blocks scheduling or cancelling. Whether a job runs or is cancelled is decided by one compare-and-set on its status: {@code SCHEDULED -> RUNNING} (fire) against {@code SCHEDULED -> CANCELLED} (cancel). Exactly one can win.
 *
 * <p>Time. {@link #runDue()} reads the ticker once on entry and runs everything due at that reading. Nothing here calls a real clock.
 */
public final class TimerScheduler implements JobScheduler {

    private final Ticker ticker;
    private final TimerQueue queue;
    private final Consumer<JobHandle> afterPoll;
    private final Consumer<JobHandle> beforeCancelCas;
    private final ReentrantLock lock = new ReentrantLock();
    private static final int DEAD_LETTER_CAP = 1000;

    private final Deque<FailedJob> dead = new ArrayDeque<>();
    private long nextId = 1;
    private long nextSeq;
    private int pending;

    /** Creates a scheduler around the given queue; use the static factories instead. */
    private TimerScheduler(Ticker ticker, TimerQueue queue, Consumer<JobHandle> afterPoll, Consumer<JobHandle> beforeCancelCas) {
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.queue = queue;
        this.afterPoll = afterPoll;
        this.beforeCancelCas = beforeCancelCas;
    }

    /** Creates a scheduler backed by a binary heap: simple, O(log n) per operation. */
    public static TimerScheduler withHeap(Ticker ticker) {
        return new TimerScheduler(ticker, new HeapTimerQueue(), j -> { }, j -> { });
    }

    /**
     * Creates a scheduler backed by a hashed timing wheel: O(1) add and cancel, precision of one tick.
     *
     * @param tick the length of one tick; jobs can fire up to one tick late, never early
     * @param wheelSize number of buckets; a power of two
     */
    public static TimerScheduler withWheel(Ticker ticker, Duration tick, int wheelSize) {
        return new TimerScheduler(ticker, new TimingWheel(ticker.read(), tick.toNanos(), wheelSize), j -> { }, j -> { });
    }

    /** Test seam: a heap scheduler that calls {@code afterPoll} for each job after it leaves the queue and before it is claimed. A test uses it to force a cancel into exactly that gap. */
    static TimerScheduler withHeapAndHook(Ticker ticker, Consumer<JobHandle> afterPoll) {
        return new TimerScheduler(ticker, new HeapTimerQueue(), afterPoll, j -> { });
    }

    /** Queues a one-shot job after a delay. */
    /** Test seam: any queue, plus two hooks. {@code afterPoll} runs after a job leaves the queue and before it is claimed; {@code beforeCancelCas} runs inside {@code cancel} after it reads the status and before it tries to change it. */
    static TimerScheduler forTest(Ticker ticker, TimerQueue queue, Consumer<JobHandle> afterPoll, Consumer<JobHandle> beforeCancelCas) {
        return new TimerScheduler(ticker, queue, afterPoll, beforeCancelCas);
    }

    /** Queues a one-shot job after a delay. */
    @Override
    public JobHandle schedule(Job job, Duration delay) {
        return enqueueNew(job, ScheduledJob.Mode.ONCE, 0, null, null, nonNegative(delay));
    }

    /** Queues a one-shot job for an absolute ticker reading; a past time means run now. */
    @Override
    public JobHandle scheduleAt(Job job, long dueNanos) {
        return enqueueNew(job, ScheduledJob.Mode.ONCE, 0, null, null, Math.max(0, saturatedSub(dueNanos, ticker.read())));
    }

    /** Queues a fixed-rate job. */
    @Override
    public JobHandle scheduleAtFixedRate(Job job, Duration initialDelay, Duration period) {
        return scheduleAtFixedRate(job, initialDelay, period, MissedRunPolicy.CATCH_UP);
    }

    /** Queues a fixed-rate job. */
    @Override
    public JobHandle scheduleAtFixedRate(Job job, Duration initialDelay, Duration period, MissedRunPolicy missed) {
        return enqueueNew(job, ScheduledJob.Mode.FIXED_RATE, positive(period), Objects.requireNonNull(missed, "missed"), null, nonNegative(initialDelay));
    }

    /** Queues a fixed-delay job. */
    @Override
    public JobHandle scheduleWithFixedDelay(Job job, Duration initialDelay, Duration delay) {
        return enqueueNew(job, ScheduledJob.Mode.FIXED_DELAY, positive(delay), null, null, nonNegative(initialDelay));
    }

    /** Queues a one-shot job that retries with backoff when it throws. */
    @Override
    public JobHandle scheduleWithRetry(Job job, Duration delay, RetryPolicy policy) {
        return enqueueNew(job, ScheduledJob.Mode.ONCE, 0, null, Objects.requireNonNull(policy, "policy"), nonNegative(delay));
    }

    /** Adds two longs, clamping at the ends instead of wrapping around. Same idea as the overflow guard in {@code ScheduledThreadPoolExecutor.triggerTime}. */
    static long saturatedAdd(long a, long b) {
        long r = a + b;
        if (((a ^ r) & (b ^ r)) < 0) {
            return a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return r;
    }

    /** Subtracts two longs, clamping at the ends instead of wrapping around. */
    private static long saturatedSub(long a, long b) {
        long r = a - b;
        if (((a ^ b) & (a ^ r)) < 0) {
            return a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return r;
    }

    /** Converts a Duration to nanoseconds, clamping one too big for a long to {@code Long.MAX_VALUE} (a Duration can hold billions of years; a long of nanoseconds only about 292). */
    private static long nanos(Duration d) {
        try {
            return d.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    /** Converts a delay to nanoseconds and rejects a negative one. */
    private static long nonNegative(Duration d) {
        if (d.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        return nanos(d);
    }

    /** Converts a period to nanoseconds and rejects zero or negative ones, which would make a repeating job run forever in one burst. */
    private static long positive(Duration d) {
        if (d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException("period must be positive");
        }
        return nanos(d);
    }

    /** Builds the job record, stamps its first due time ({@code now + delayNanos}, clamped at the far end) and queues it. */
    private JobHandle enqueueNew(Job job, ScheduledJob.Mode mode, long interval, MissedRunPolicy missed, RetryPolicy retry, long delayNanos) {
        Objects.requireNonNull(job, "job");
        lock.lock();
        try {
            ScheduledJob sj = new ScheduledJob(nextId++, job, mode, interval, missed, retry, this);
            sj.due = saturatedAdd(ticker.read(), delayNanos);
            sj.seq = nextSeq++;
            queue.add(sj);
            pending++;
            return sj;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Cancels a job. See {@link JobHandle#cancel()} for the contract.
     *
     * <p>The status can change between reading it and changing it (a repeating job finishes a run and goes back to SCHEDULED), so this loops: read the status, try the matching change, and if the change lost a race read again. It only returns false when the job really cannot be cancelled (finished, already cancelled, or a plain one-shot already running).
     */
    boolean cancel(ScheduledJob sj) {
        while (true) {
            JobStatus seen = sj.status.get();
            beforeCancelCas.accept(sj);
            switch (seen) {
                case SCHEDULED -> {
                    if (sj.status.compareAndSet(JobStatus.SCHEDULED, JobStatus.CANCELLED)) {
                        lock.lock();
                        try {
                            queue.remove(sj);
                            pending--;
                        } finally {
                            lock.unlock();
                        }
                        return true;
                    }
                }
                case RUNNING -> {
                    // Only a job that would run again can be stopped mid-run: a repeating job (no next run) or a retrying job (no more retries).
                    if (!sj.periodic() && sj.retry == null) {
                        return false;
                    }
                    if (sj.status.compareAndSet(JobStatus.RUNNING, JobStatus.CANCELLED)) {
                        return true;
                    }
                }
                default -> {
                    return false;
                }
            }
        }
    }

    /** Claims and runs every due job, skipping any that a cancel won; the job body runs outside the lock. */
    @Override
    public int runDue() {
        long now = ticker.read();
        int started = 0;
        while (true) {
            ScheduledJob sj;
            lock.lock();
            try {
                sj = queue.pollDue(now);
            } finally {
                lock.unlock();
            }
            if (sj == null) {
                return started;
            }
            afterPoll.accept(sj);
            if (!sj.status.compareAndSet(JobStatus.SCHEDULED, JobStatus.RUNNING)) {
                continue; // lost the race to a cancel: the job never runs
            }
            lock.lock();
            try {
                pending--;
            } finally {
                lock.unlock();
            }
            started++;
            execute(sj);
        }
    }

    /**
     * Runs the job body once, catching everything it throws, then decides what happens next: finish, retry, dead-letter, or queue the next periodic run.
     *
     * <p>It catches {@code Throwable}, not just {@code Exception}, the same way {@code FutureTask} does: a job that throws an {@code Error} must not take the driver down. After a {@code VirtualMachineError} (out of memory) the JVM gives no guarantees anyway. An {@code InterruptedException} is recorded as a failure and the thread's interrupt flag is set again, so the caller of {@code runDue} still sees that it was interrupted.
     */
    private void execute(ScheduledJob sj) {
        int attempt = sj.attempts.incrementAndGet();
        Throwable error = null;
        try {
            sj.job.run();
        } catch (Throwable t) {
            error = t;
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        long scheduledFor = sj.due;
        long end = ticker.read();
        switch (sj.mode) {
            case ONCE -> {
                if (error == null) {
                    sj.status.compareAndSet(JobStatus.RUNNING, JobStatus.DONE);
                } else if (sj.retry != null && attempt < sj.retry.maxAttempts()) {
                    requeue(sj, saturatedAdd(end, nanos(sj.retry.delayAfter(attempt))));
                } else if (sj.status.compareAndSet(JobStatus.RUNNING, JobStatus.DONE)) {
                    deadLetter(sj, attempt, error);
                }
            }
            case FIXED_RATE -> {
                if (error != null) {
                    deadLetter(sj, attempt, error);
                }
                long next = saturatedAdd(scheduledFor, sj.intervalNanos);
                if (sj.missed == MissedRunPolicy.COALESCE && next < end) {
                    next = saturatedAdd(scheduledFor, ((end - scheduledFor) / sj.intervalNanos + 1) * sj.intervalNanos);
                }
                requeue(sj, next);
            }
            case FIXED_DELAY -> {
                if (error != null) {
                    deadLetter(sj, attempt, error);
                }
                requeue(sj, saturatedAdd(end, sj.intervalNanos));
            }
        }
    }

    /** Puts a job back in the queue for its next run, unless it was cancelled while running (then the compare-and-set fails and nothing is queued). */
    private void requeue(ScheduledJob sj, long due) {
        lock.lock();
        try {
            if (sj.status.compareAndSet(JobStatus.RUNNING, JobStatus.SCHEDULED)) {
                sj.due = due;
                queue.add(sj); // keeps its original sequence number, so it keeps its place among same-instant jobs
                pending++;
            }
        } finally {
            lock.unlock();
        }
    }

    /** Adds a failure to the dead-letter list, dropping the oldest entry once there are 1,000. */
    private void deadLetter(ScheduledJob sj, int attempts, Throwable error) {
        lock.lock();
        try {
            dead.addLast(new FailedJob(sj.id, attempts, error));
            if (dead.size() > DEAD_LETTER_CAP) {
                dead.removeFirst();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Returns the number of jobs waiting for a future run. */
    @Override
    public int pending() {
        lock.lock();
        try {
            return pending;
        } finally {
            lock.unlock();
        }
    }

    /** Returns a copy of the failed-job list. */
    @Override
    public List<FailedJob> deadLetters() {
        lock.lock();
        try {
            return new ArrayList<>(dead);
        } finally {
            lock.unlock();
        }
    }
}
