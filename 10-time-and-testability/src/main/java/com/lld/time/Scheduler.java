package com.lld.time;

import java.time.Duration;

/**
 * The one seam between domain code and "run this later".
 *
 * <p>Domain classes depend on this interface, never on a {@code ScheduledExecutorService}
 * directly. Production wires {@link ExecutorScheduler}. Tests wire {@link ManualScheduler} and
 * advance virtual time, so a 10-minute expiry is tested in microseconds with no
 * {@code Thread.sleep}.
 */
public interface Scheduler {

    Cancellable schedule(Duration delay, Runnable task);
}
