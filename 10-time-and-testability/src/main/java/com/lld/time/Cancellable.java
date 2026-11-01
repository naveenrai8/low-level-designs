package com.lld.time;

/**
 * A handle to a scheduled task, used to cancel it before it runs.
 */
@FunctionalInterface
public interface Cancellable {

    /**
     * Tries to cancel the task.
     * 
     * @return {@code true} if the task was cancelled before it ran.
     */
    boolean cancel();
}
