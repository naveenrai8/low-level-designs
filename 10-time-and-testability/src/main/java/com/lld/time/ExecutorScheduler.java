package com.lld.time;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Production {@link Scheduler} backed by a real {@code ScheduledExecutorService}. The caller owns the executor's lifecycle (shutdown), as described in Topic 09.
 */
public final class ExecutorScheduler implements Scheduler {

    private final ScheduledExecutorService executor;

    /**
     * Wraps an executor that the caller creates and shuts down.
     */
    public ExecutorScheduler(ScheduledExecutorService executor) {
        this.executor = executor;
    }

    /**
     * Runs the task after the delay on the executor's thread. Cancelling does not interrupt a task that is already running.
     */
    @Override
    public Cancellable schedule(Duration delay, Runnable task) {
        ScheduledFuture<?> future = executor.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
        return () -> future.cancel(false);
    }
}
