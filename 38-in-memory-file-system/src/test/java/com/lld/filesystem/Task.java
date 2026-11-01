package com.lld.filesystem;

import java.lang.management.ManagementFactory;

/**
 * A test thread that remembers what its body threw, so the test thread can look at it after the join.
 * <p>It is a daemon, so a deadlocked task cannot keep the JVM alive.
 */
final class Task extends Thread {
    private final Runnable body;
    volatile Throwable error;

    /**
     * Creates the task.
     *
     * @param name thread name, which hooks may check
     * @param body the work
     */
    Task(String name, Runnable body) {
        super(name);
        this.body = body;
        setDaemon(true);
    }

    /** Runs the body and stores anything it throws. */
    @Override
    public void run() {
        try {
            body.run();
        } catch (Throwable t) {
            error = t;
        }
    }

    /**
     * Gives the error kind the task failed with, or null when it succeeded.
     *
     * @return the kind, or null
     */
    Errno errno() {
        return error instanceof FsException e ? e.errno() : null;
    }

    /**
     * Waits for the tasks, and fails the test if one is stuck or died with anything other than an {@link FsException}.
     * <p>{@code ThreadMXBean.findDeadlockedThreads} is only the safety net that explains a hang. An {@code FsException} is allowed through because some tests expect it; the test then reads {@link #errno()}.
     *
     * @param tasks the tasks to join
     * @throws InterruptedException if the test thread is interrupted
     */
    static void joinAll(Task... tasks) throws InterruptedException {
        for (Task t : tasks) {
            t.join(5000);
        }
        for (Task t : tasks) {
            if (t.isAlive()) {
                long[] dead = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
                throw new AssertionError("task " + t.getName() + " is stuck; deadlocked threads: " + (dead == null ? "none" : String.valueOf(dead.length)));
            }
            if (t.error != null && !(t.error instanceof FsException)) {
                throw new AssertionError("task " + t.getName() + " failed", t.error);
            }
        }
    }
}
