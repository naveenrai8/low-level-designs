package com.lld.time;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * Deterministic test {@link Scheduler}. Tasks run only inside {@link #advance}, on the calling thread, in due-time order (ties in scheduling order).
 * <p>It drives a {@link MutableClock}, so while a task runs, {@code clock.instant()} equals that task's due time, just as it would in production if the timer fired exactly on time.
 * <p>Single-threaded by design. It is a test double, not a concurrent scheduler.
 */
public final class ManualScheduler implements Scheduler {

    /**
     * One waiting task: when it is due, its scheduling order (to break ties), and what to run.
     */
    private record Task(Instant due, long seq, Runnable action) { }

    private final MutableClock clock;
    private final PriorityQueue<Task> queue =
            new PriorityQueue<>(Comparator.comparing(Task::due).thenComparingLong(Task::seq));
    private long nextSeq;

    /**
     * Creates a scheduler that moves the given clock as virtual time passes.
     */
    public ManualScheduler(MutableClock clock) {
        this.clock = clock;
    }

    /**
     * Queues the task to be due {@code delay} after the clock's current time. Nothing runs until {@link #advance}. Cancelling removes it from the queue.
     */
    @Override
    public Cancellable schedule(Duration delay, Runnable action) {
        Task task = new Task(clock.instant().plus(delay), nextSeq++, action);
        queue.add(task);
        return () -> queue.remove(task);
    }

    /**
     * Moves virtual time forward by {@code by}, running every task that falls due on the way.
     * <p>Tasks scheduled by a running task also run if they fall due inside the window. Throws {@code IllegalArgumentException} for a negative duration, because virtual time only moves forward.
     */
    public void advance(Duration by) {
        if (by.isNegative()) {
            throw new IllegalArgumentException("virtual time only moves forward: " + by);
        }
        Instant target = clock.instant().plus(by);
        while (!queue.isEmpty() && !queue.peek().due().isAfter(target)) {
            Task task = queue.poll();
            if (task.due().isAfter(clock.instant())) {
                clock.setInstant(task.due());
            }
            task.action().run();
        }
        clock.setInstant(target);
    }

    /**
     * Counts tasks still waiting to run.
     */
    public int pending() {
        return queue.size();
    }
}
