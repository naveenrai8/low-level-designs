package com.lld.blockingqueue;

import com.lld.blockingqueue.QueueHooks.Side;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A bounded blocking queue built from one lock and two conditions (waiting rooms), the same design as the JDK's {@code ArrayBlockingQueue}.
 *
 * <p>Shared state: the ring buffer {@code items}, {@code head}, {@code count} and {@code closed}. Invariant: {@code 0 <= count <= capacity}, and the oldest item is at {@code head}. Every read or write of that state happens while holding {@code lock}.
 *
 * <p>Two waiting rooms: {@code notEmpty} holds takers, {@code notFull} holds putters. Because each room only ever holds one kind of thread, a single {@code signal()} always wakes a thread that can actually make progress. With one shared room it could wake the wrong kind and the signal would be lost.
 *
 * <p>Every wait sits inside a {@code while} loop that re-checks the condition after waking. A woken thread has to win the lock again, and another thread may have used the item first (a "stolen wakeup"). A wait can also end with no signal at all (a "spurious wakeup").
 */
public final class BoundedBlockingQueue<E> implements BoundedQueue<E> {

    private final Object[] items;
    private final Ticker ticker;
    private final QueueHooks hooks;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private int head;
    private int count;
    private boolean closed;

    /** Creates a queue with the given capacity (at least 1), a real clock for timeouts and no test hooks. */
    public BoundedBlockingQueue(int capacity) {
        this(capacity, Ticker.system(), QueueHooks.NONE);
    }

    /** Creates a queue with an injected clock (for timed calls) and test hooks. Throws {@link IllegalArgumentException} if capacity is below 1. */
    public BoundedBlockingQueue(int capacity, Ticker ticker, QueueHooks hooks) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1, got " + capacity);
        }
        this.items = new Object[capacity];
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.hooks = Objects.requireNonNull(hooks, "hooks");
    }

    /** {@inheritDoc} */
    @Override
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "e");
        lock.lockInterruptibly();
        try {
            // while, not if: after waking we must re-check, because someone else may have filled the slot first
            while (count == items.length && !closed) {
                hooks.beforeAwait(Side.NOT_FULL);
                notFull.await();
            }
            requireOpen("put");
            enqueue(e);
        } finally {
            lock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            // while, not if: after waking we must re-check, because someone else may have taken the item first
            while (count == 0 && !closed) {
                hooks.beforeAwait(Side.NOT_EMPTY);
                notEmpty.await();
            }
            if (count == 0) {
                throw new QueueClosedException("take on a closed, empty queue");
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean offer(E e) {
        Objects.requireNonNull(e, "e");
        lock.lock();
        try {
            requireOpen("offer");
            if (count == items.length) {
                return false;
            }
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e, "e");
        long timeoutNanos = unit.toNanos(timeout);
        long start = ticker.read();
        lock.lockInterruptibly();
        try {
            while (count == items.length && !closed) {
                long remaining = timeoutNanos - (ticker.read() - start);
                if (remaining <= 0) {
                    return false;
                }
                hooks.beforeAwait(Side.NOT_FULL);
                notFull.awaitNanos(remaining);
            }
            requireOpen("offer");
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public E poll() {
        lock.lock();
        try {
            return count == 0 ? null : dequeue();
        } finally {
            lock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long timeoutNanos = unit.toNanos(timeout);
        long start = ticker.read();
        lock.lockInterruptibly();
        try {
            while (count == 0 && !closed) {
                long remaining = timeoutNanos - (ticker.read() - start);
                if (remaining <= 0) {
                    return null;
                }
                hooks.beforeAwait(Side.NOT_EMPTY);
                notEmpty.awaitNanos(remaining);
            }
            return count == 0 ? null : dequeue();
        } finally {
            lock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public int capacity() {
        return items.length;
    }

    /** {@inheritDoc} Uses {@code signalAll} on both rooms: here we want <em>every</em> waiter to wake and see the closed flag, not just one. */
    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean isClosed() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<E> drainAll() {
        lock.lock();
        try {
            List<E> out = new ArrayList<>(count);
            while (count > 0) {
                out.add(dequeue());
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    /** {@inheritDoc} Close and drain share one lock hold, which is what makes {@code shutdownNow} exact. */
    @Override
    public List<E> closeAndDrain() {
        lock.lock();
        try {
            closed = true;
            notEmpty.signalAll();
            notFull.signalAll();
            List<E> out = new ArrayList<>(count);
            while (count > 0) {
                out.add(dequeue());
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    /** Test probe: counts the buffer slots that still hold a reference. After every item is taken this must be 0, otherwise the queue leaks the tasks it already handed out. */
    int occupiedSlots() {
        lock.lock();
        try {
            int n = 0;
            for (Object o : items) {
                if (o != null) {
                    n++;
                }
            }
            return n;
        } finally {
            lock.unlock();
        }
    }

    /** Throws {@link QueueClosedException} if the queue is closed. The caller must hold the lock. */
    private void requireOpen(String operation) {
        if (closed) {
            throw new QueueClosedException(operation + " on a closed queue");
        }
    }

    /** Writes {@code e} at the tail of the ring and wakes one taker. The caller must hold the lock and have checked there is room. */
    private void enqueue(E e) {
        items[(head + count) % items.length] = e;
        count++;
        notEmpty.signal();
        hooks.afterSignal(Side.NOT_EMPTY);
    }

    /** Removes the oldest item and wakes one putter. The caller must hold the lock and have checked the queue is not empty. */
    @SuppressWarnings("unchecked")
    private E dequeue() {
        E e = (E) items[head];
        items[head] = null; // let the garbage collector reclaim it
        head = (head + 1) % items.length;
        count--;
        notFull.signal();
        hooks.afterSignal(Side.NOT_FULL);
        return e;
    }
}
