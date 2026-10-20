package com.lld.time;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Production {@link Scheduler}. The caller owns the executor's lifecycle (shutdown), as described
 * in Topic 09.
 */
public final class ExecutorScheduler implements Scheduler {

    private final ScheduledExecutorService executor;

    public ExecutorScheduler(ScheduledExecutorService executor) {
        this.executor = executor;
    }

    @Override
    public Cancellable schedule(Duration delay, Runnable task) {
        ScheduledFuture<?> future = executor.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
        return () -> future.cancel(false);
    }
}
