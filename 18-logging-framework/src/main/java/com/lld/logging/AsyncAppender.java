package com.lld.logging;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Puts a bounded queue and one background thread in front of a slow appender, so the caller's thread never waits for disk or network.
 *
 * <p>The queue holds at most {@code capacity} events. When it is full the {@link DropPolicy} decides what is lost. Nothing is lost silently: every loss is counted.
 *
 * <p>Counting rule: {@code offered} is every call to {@link #append}. {@code dropped} is every event thrown away by the policy or after close (the new one, or the oldest one evicted to make room). {@link #accepted()} is just {@code offered - dropped}, so it is a convenience and proves nothing by itself. The real rule, which the tests assert once things settle, is {@code offered == delivered + delegateFailures + queued + abandoned + workerDeadDrops + dropped}. Each term is counted at a different place, so an event that vanished would break it.
 *
 * <p>Worker death: if the delegate throws an {@link Error}, the worker records its death, counts the event it was writing and everything still queued as {@link #workerDeadDrops()}, wakes any blocked producers and rethrows the error. From then on every {@link #append} counts as a worker-dead drop (it is neither queued nor blocked).
 *
 * <p>Shutdown: {@link #close()} stops taking new events (later ones count as dropped), lets the worker write everything still queued, then closes the delegate. It is safe to call twice, and a second caller waits until the first has finished. The wait for the worker has no timeout: a delegate that never returns makes close wait forever, so interrupt the closing thread to give up (see {@link #close()}).
 */
public final class AsyncAppender implements Appender {

    /** What to do when an event arrives and the queue is full. */
    public enum DropPolicy {
        /** Throw away the event that just arrived. Keeps the oldest lines, which usually show the root cause. */
        DROP_NEWEST,
        /** Throw away the oldest queued event to make room. Keeps the freshest lines. */
        DROP_OLDEST,
        /** Make the caller wait up to a timeout for space, then drop the new event. Trades caller latency for fewer drops. */
        BLOCK_WITH_TIMEOUT
    }

    /** How long an interrupted close waits for the event being written to finish. */
    private static final long GRACE_MILLIS = 5_000;

    private final Appender delegate;
    private final int capacity;
    private final DropPolicy policy;
    private final long blockNanos;
    private final Ticker ticker;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private final ArrayDeque<LogEvent> queue = new ArrayDeque<>();
    private boolean closed;
    private boolean closeStarted;
    private boolean abandonRequested;
    private boolean workerDead;
    private int blockedProducers;
    private final CountDownLatch closeDone = new CountDownLatch(1);

    private final AtomicLong offered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong delegateFailures = new AtomicLong();
    private final AtomicLong workerDeadDrops = new AtomicLong();
    private volatile long abandoned;
    private final Thread worker;

    /**
     * Creates the appender and starts its worker thread.
     *
     * @param delegate   where events finally go
     * @param capacity   maximum queued events, at least 1
     * @param policy     what to do when full
     * @param blockNanos how long BLOCK_WITH_TIMEOUT waits (ignored by the other policies)
     * @param ticker     measures that wait; tests pass a fake one
     * @param threads    makes the worker thread (so the owner can name it or make it a daemon)
     */
    public AsyncAppender(Appender delegate, int capacity, DropPolicy policy, long blockNanos, Ticker ticker, ThreadFactory threads) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.delegate = Objects.requireNonNull(delegate);
        this.capacity = capacity;
        this.policy = Objects.requireNonNull(policy);
        this.blockNanos = blockNanos;
        this.ticker = Objects.requireNonNull(ticker);
        this.worker = threads.newThread(this::drainLoop);
        this.worker.start();
    }

    /** Convenience: a daemon worker thread and the real ticker. */
    public AsyncAppender(Appender delegate, int capacity, DropPolicy policy, long blockNanos) {
        this(delegate, capacity, policy, blockNanos, Ticker.system(), r -> {
            Thread t = new Thread(r, "async-appender");
            t.setDaemon(true);
            return t;
        });
    }

    /** Queues the event, or drops something according to the policy. Never throws and never writes to the delegate on the caller's thread. */
    @Override
    public void append(LogEvent event) {
        offered.incrementAndGet();
        lock.lock();
        try {
            if (workerDead) {
                workerDeadDrops.incrementAndGet();
                return;
            }
            if (closed) {
                dropped.incrementAndGet();
                return;
            }
            if (queue.size() >= capacity) {
                switch (policy) {
                    case DROP_NEWEST -> {
                        dropped.incrementAndGet();
                        return;
                    }
                    case DROP_OLDEST -> {
                        queue.pollFirst();
                        dropped.incrementAndGet();
                    }
                    case BLOCK_WITH_TIMEOUT -> {
                        if (!waitForSpace()) {
                            (workerDead ? workerDeadDrops : dropped).incrementAndGet();
                            return;
                        }
                    }
                }
            }
            queue.addLast(event);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits (lock held) until there is room, the appender closes, the worker dies or the timeout passes. Returns true if there is room.
     *
     * <p>The deadline is read from the injected ticker and the wait lasts exactly the remaining time. A test that moves the fake ticker calls {@link #wake()} so the waiter looks at the ticker again.
     */
    private boolean waitForSpace() {
        long deadline = ticker.read() + blockNanos;
        blockedProducers++;
        try {
            while (queue.size() >= capacity && !closed && !workerDead) {
                long remaining = deadline - ticker.read();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    notFull.awaitNanos(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return !closed && !workerDead;
        } finally {
            blockedProducers--;
        }
    }

    /** Wakes every producer waiting for space so it re-reads the ticker. Only tests need it, after they move a fake ticker. */
    void wake() {
        lock.lock();
        try {
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** The worker thread's loop. A delegate exception is counted and the loop carries on. Anything worse (an Error) is recorded by {@link #workerDied} and rethrown. */
    private void drainLoop() {
        LogEvent inFlight = null;
        try {
            while (true) {
                lock.lock();
                try {
                    while (queue.isEmpty()) {
                        if (closed) {
                            return;
                        }
                        notEmpty.awaitUninterruptibly();
                    }
                    if (abandonRequested) {
                        return;
                    }
                    inFlight = queue.pollFirst();
                    notFull.signal();
                } finally {
                    lock.unlock();
                }
                try {
                    delegate.append(inFlight);
                    delivered.incrementAndGet();
                } catch (RuntimeException ex) {
                    delegateFailures.incrementAndGet();
                }
                inFlight = null;
            }
        } catch (Throwable t) {
            workerDied(inFlight != null);
            throw t;
        }
    }

    /** Marks the worker dead, counts the event it was writing (if any) and everything still queued as worker-dead drops, and wakes blocked producers. */
    private void workerDied(boolean hadInFlight) {
        lock.lock();
        try {
            workerDead = true;
            workerDeadDrops.addAndGet(queue.size() + (hadInFlight ? 1 : 0));
            queue.clear();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stops accepting events, waits for the worker to write what is queued, then closes the delegate.
     *
     * <p>Only the first caller does the work. A later caller waits until the first has finished, so when any close returns the delegate is closed.
     *
     * <p>If the closing thread is interrupted while waiting, it stops waiting for the queue: the events still queued are counted once, exactly, in {@link #abandoned()} and discarded, the worker is given a short grace period to finish the event it is writing, the delegate is closed anyway and the interrupt flag is restored.
     */
    @Override
    public void close() {
        boolean owner;
        lock.lock();
        try {
            owner = !closeStarted;
            if (owner) {
                closeStarted = true;
                closed = true;
                notEmpty.signalAll();
                notFull.signalAll();
            }
        } finally {
            lock.unlock();
        }
        if (!owner) {
            awaitFirstCloser();
            return;
        }
        boolean interrupted = false;
        try {
            worker.join();
        } catch (InterruptedException e) {
            interrupted = true;
            lock.lock();
            try {
                abandoned = queue.size();
                queue.clear();
                abandonRequested = true;
                notEmpty.signalAll();
            } finally {
                lock.unlock();
            }
            try {
                worker.join(GRACE_MILLIS);
            } catch (InterruptedException again) {
                // Already giving up: keep going to close the delegate.
            }
        } finally {
            try {
                delegate.close();
            } finally {
                closeDone.countDown();
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** Waits for the first closer to finish. If this thread is interrupted it stops waiting and keeps its interrupt flag. */
    private void awaitFirstCloser() {
        try {
            closeDone.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Returns true once some thread has started closing. Lets a test know close has begun. */
    boolean closing() {
        lock.lock();
        try {
            return closeStarted;
        } finally {
            lock.unlock();
        }
    }

    /** Returns true once an interrupted close has discarded the queue. Lets a test know the abandon step is done. */
    boolean abandoning() {
        lock.lock();
        try {
            return abandonRequested;
        } finally {
            lock.unlock();
        }
    }

    /** Returns true if the worker thread died from an Error. */
    public boolean workerDead() {
        lock.lock();
        try {
            return workerDead;
        } finally {
            lock.unlock();
        }
    }

    /** Events lost because the worker died: the one it was writing, the ones queued at that moment and every later append. */
    public long workerDeadDrops() {
        return workerDeadDrops.get();
    }

    /** Total calls to {@link #append}. */
    public long offered() {
        return offered.get();
    }

    /** Events thrown away: queue full, or arrived after close. */
    public long dropped() {
        return dropped.get();
    }

    /** Derived convenience: offered minus dropped. Not an independent count, so it cannot prove the accounting; see the class comment for the real rule. */
    public long accepted() {
        return offered.get() - dropped.get();
    }

    /** Events the delegate has written successfully. */
    public long delivered() {
        return delivered.get();
    }

    /** Events the delegate threw on. They are lost, but counted here. */
    public long delegateFailures() {
        return delegateFailures.get();
    }

    /** Events discarded from the queue because close was interrupted. Zero after a normal close. */
    public long abandoned() {
        return abandoned;
    }

    /** Returns how many events are queued right now. */
    public int queued() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    /** Returns how many callers are currently waiting for space. Lets a test know a producer is parked without sleeping. */
    int blockedProducers() {
        lock.lock();
        try {
            return blockedProducers;
        } finally {
            lock.unlock();
        }
    }
}
