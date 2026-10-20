package com.lld.time;

/** Handle to a scheduled task. */
@FunctionalInterface
public interface Cancellable {

    /** @return {@code true} if the task was cancelled before it ran. */
    boolean cancel();
}
