package com.lld.dispatch;

import java.util.PriorityQueue;

/** Virtual time: nothing runs until a test calls {@link #advance}. Tasks due at the same time run in the order they were scheduled. */
public final class ManualScheduler implements Scheduler {

    /** One scheduled task. */
    private static final class Task implements Cancellable, Comparable<Task> {
        final long at;
        final long seq;
        final Runnable run;
        boolean cancelled;

        /** Creates a task due at a time, with a sequence number to break ties. */
        Task(long at, long seq, Runnable run) {
            this.at = at;
            this.seq = seq;
            this.run = run;
        }

        /** Marks the task so it is skipped. */
        @Override
        public void cancel() {
            cancelled = true;
        }

        /** Orders by time, then by scheduling order. */
        @Override
        public int compareTo(Task o) {
            return at != o.at ? Long.compare(at, o.at) : Long.compare(seq, o.seq);
        }
    }

    private final PriorityQueue<Task> queue = new PriorityQueue<>();
    private long now;
    private long seq;

    /** Returns the virtual time. */
    @Override
    public synchronized long nowMs() {
        return now;
    }

    /** Queues the task to run at now + delay. */
    @Override
    public synchronized Cancellable schedule(long delayMs, Runnable task) {
        Task t = new Task(Math.addExact(now, Math.max(0, delayMs)), seq++, task);
        queue.add(t);
        return t;
    }

    /** Counts queued tasks that are not cancelled; lets a test prove a timer really was cancelled. */
    public synchronized int pendingCount() {
        int n = 0;
        for (Task t : queue) {
            if (!t.cancelled) {
                n++;
            }
        }
        return n;
    }

    /**
     * Moves time forward, running every task that comes due on the way, each with the clock set to its own due time.
     *
     * @param ms how far to move
     */
    public void advance(long ms) {
        if (ms < 0) {
            throw new IllegalArgumentException("cannot move time backwards: " + ms);
        }
        long target;
        synchronized (this) {
            target = Math.addExact(now, ms);
        }
        while (true) {
            Task t;
            synchronized (this) {
                t = queue.peek();
                if (t == null || t.at > target) {
                    now = target;
                    return;
                }
                queue.poll();
                now = Math.max(now, t.at);
                if (t.cancelled) {
                    continue;
                }
            }
            t.run.run();
        }
    }
}
