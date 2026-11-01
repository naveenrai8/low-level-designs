package com.lld.blockingqueue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A pool with a fixed number of worker threads that read tasks from a {@link BoundedQueue}.
 *
 * <p>Workers are started in the constructor (the JDK starts them lazily). Each worker loops: take a task, run it, repeat. A worker leaves the loop when the queue is closed and empty ({@link #shutdown()}), or when it is interrupted after {@link #shutdownNow()}.
 *
 * <p>Graceful shutdown needs no interrupts and no poison pills: {@code shutdown} just closes the queue. Closing refuses new tasks atomically (the queue's own lock decides), lets workers drain what is left, and wakes idle workers so they exit.
 *
 * <p>Compare with {@code ThreadPoolExecutor}: same idea, but the JDK also grows and shrinks the number of threads, has a state machine packed into one atomic int, and has hooks such as {@code beforeExecute}.
 */
public final class FixedThreadPool implements TaskExecutor {

    private final BoundedQueue<Runnable> queue;
    private final RejectionPolicy policy;
    private final Ticker ticker;
    private final List<Thread> workers = new ArrayList<>();
    private final ReentrantLock termLock = new ReentrantLock();
    private final Condition terminated = termLock.newCondition();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private int liveWorkers; // guarded by termLock
    private volatile boolean stopNow;

    /** Creates a pool with simple defaults: abort policy, real clock, plain non-daemon threads. */
    public FixedThreadPool(int threads, int queueCapacity) {
        this(threads, queueCapacity, RejectionPolicies.abort(), Ticker.system(), Thread::new);
    }

    /**
     * Creates the pool and starts all worker threads right away.
     *
     * @param threads number of worker threads, at least 1
     * @param queueCapacity how many tasks may wait, at least 1
     * @param policy what to do with a refused task
     * @param ticker clock for {@link #awaitTermination}
     * @param threadFactory makes the worker threads (tests make them daemon so a failed test cannot hang the JVM)
     */
    public FixedThreadPool(int threads, int queueCapacity, RejectionPolicy policy, Ticker ticker, ThreadFactory threadFactory) {
        this(threads, new BoundedBlockingQueue<>(queueCapacity), policy, ticker, threadFactory);
    }

    /** Package-private: lets a test hand in its own queue (for example one that pauses between steps) so pool interleavings can be forced. */
    FixedThreadPool(int threads, BoundedQueue<Runnable> queue, RejectionPolicy policy, Ticker ticker, ThreadFactory threadFactory) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be at least 1, got " + threads);
        }
        this.queue = Objects.requireNonNull(queue, "queue");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        Objects.requireNonNull(threadFactory, "threadFactory");
        this.liveWorkers = threads;
        for (int i = 0; i < threads; i++) {
            Thread worker = threadFactory.newThread(this::workerLoop);
            workers.add(worker);
        }
        for (Thread worker : workers) {
            worker.start();
        }
    }

    /** {@inheritDoc} Never waits: a full queue goes straight to the rejection policy. */
    @Override
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        try {
            if (queue.offer(task)) {
                return;
            }
        } catch (QueueClosedException closed) {
            // fall through: a shut-down pool refuses work through the same policy
        }
        policy.rejected(task, this);
    }

    /** {@inheritDoc} */
    @Override
    public void shutdown() {
        queue.close();
    }

    /** {@inheritDoc} */
    @Override
    public List<Runnable> shutdownNow() {
        // one lock hold: a worker can never take a task between the close and the drain, so every returned task really never ran
        List<Runnable> neverRan = queue.closeAndDrain();
        stopNow = true;
        for (Thread worker : workers) {
            worker.interrupt();
        }
        return neverRan;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isShutdown() {
        return queue.isClosed();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isTerminated() {
        termLock.lock();
        try {
            return queue.isClosed() && liveWorkers == 0;
        } finally {
            termLock.unlock();
        }
    }

    /** {@inheritDoc} The remaining time is measured with the injected {@link Ticker}, so a test can make the timeout expire without waiting. */
    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long timeoutNanos = unit.toNanos(timeout);
        long start = ticker.read();
        termLock.lockInterruptibly();
        try {
            // while, not if: wake-ups can be spurious, so re-check the real condition each time
            while (!(queue.isClosed() && liveWorkers == 0)) {
                long remaining = timeoutNanos - (ticker.read() - start);
                if (remaining <= 0) {
                    return false;
                }
                terminated.awaitNanos(remaining);
            }
            return true;
        } finally {
            termLock.unlock();
        }
    }

    /** Returns how many tasks have finished without throwing. */
    public long completedTasks() {
        return completed.get();
    }

    /** Returns how many tasks ended by throwing (a {@link RuntimeException} or an {@link Error}). */
    public long failedTasks() {
        return failed.get();
    }

    /** Returns how many tasks are waiting in the queue right now. A snapshot. */
    public int queuedTasks() {
        return queue.size();
    }

    /** Removes the longest-waiting queued task and forgets it. Used by the discard-oldest policy. */
    void dropOldestQueued() {
        queue.poll();
    }

    /** The loop every worker thread runs: take, run, repeat, until the queue is closed and empty or we are told to stop now. */
    private void workerLoop() {
        try {
            while (true) {
                Runnable task;
                try {
                    task = queue.take();
                } catch (QueueClosedException closedAndEmpty) {
                    return;
                } catch (InterruptedException e) {
                    if (stopNow) {
                        return;
                    }
                    // A stray interrupt with no shutdownNow. The thread belongs to this pool, so its owner may decide what interruption means (JCIP 7.1): here it means nothing, keep working.
                    continue;
                }
                runOne(task);
            }
        } finally {
            workerExited();
        }
    }

    /**
     * Runs one task and counts the outcome. A failing task must not kill the worker, or the pool would silently lose threads, and with one thread the pool would accept tasks that never run.
     *
     * <p>It catches {@link Throwable}, so an {@link Error} such as an {@code AssertionError} (likely under {@code -ea}) also counts as failed. This is a deliberate choice: the simple alternative to the JDK's "let the thread die and start a replacement worker" (see {@code processWorkerExit}). The cost is that a fatal error like {@link OutOfMemoryError} is swallowed too, so a real service should log it.
     */
    private void runOne(Runnable task) {
        try {
            task.run();
            completed.incrementAndGet();
        } catch (Throwable e) {
            failed.incrementAndGet();
        }
    }

    /** Records that a worker has finished, and wakes {@link #awaitTermination} callers when the last one is gone. */
    private void workerExited() {
        termLock.lock();
        try {
            liveWorkers--;
            if (liveWorkers == 0) {
                terminated.signalAll();
            }
        } finally {
            termLock.unlock();
        }
    }
}
