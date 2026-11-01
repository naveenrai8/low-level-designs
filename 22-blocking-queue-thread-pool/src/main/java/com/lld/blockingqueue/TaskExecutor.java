package com.lld.blockingqueue;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * The small contract of a thread pool: hand it work, then stop it politely or at once.
 *
 * <p>Error contract: {@code execute} throws {@link NullPointerException} for a null task, and may throw {@link RejectedExecutionException} depending on the rejection policy. A task that throws a {@link RuntimeException} is counted as failed and does not kill its worker thread.
 */
public interface TaskExecutor {

    /** Queues the task to run on a worker thread. If the pool is shut down or its queue is full, the rejection policy decides what happens. */
    void execute(Runnable task);

    /** Graceful stop: refuses new tasks, but lets running and already-queued tasks finish. Returns immediately. Calling it again does nothing. */
    void shutdown();

    /** Hard stop: refuses new tasks, interrupts the workers, and returns the queued tasks that never started. A running task only stops if it reacts to interruption. */
    List<Runnable> shutdownNow();

    /** Returns whether {@link #shutdown()} or {@link #shutdownNow()} has been called. */
    boolean isShutdown();

    /** Returns whether the pool is shut down and every worker thread has finished. */
    boolean isTerminated();

    /** Waits until the pool is terminated or the timeout passes, whichever comes first. Returns {@code true} if it terminated. */
    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException;
}
