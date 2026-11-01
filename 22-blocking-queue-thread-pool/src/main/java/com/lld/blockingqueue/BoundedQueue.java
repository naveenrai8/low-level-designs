package com.lld.blockingqueue;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A first-in-first-out queue with a fixed capacity, safe for many threads.
 *
 * <p>Error contract:
 * <ul>
 * <li>{@code null} elements are rejected with {@link NullPointerException}.
 * <li>{@code put} waits while the queue is full. {@code take} waits while it is empty.
 * <li>Waiting calls throw {@link InterruptedException} if the thread is interrupted. The queue is left unchanged.
 * <li>After {@link #close()}, every put/offer throws {@link QueueClosedException}. Untimed {@code take} still receives the items already inside, and once it is empty gets {@link QueueClosedException} instead of waiting forever. The timed {@code poll} and the non-waiting {@code poll} return {@code null} for "nothing there", closed or not.
 * </ul>
 */
public interface BoundedQueue<E> {

    /** Adds {@code e}, waiting while the queue is full. Throws {@link QueueClosedException} if the queue is (or becomes) closed while waiting. */
    void put(E e) throws InterruptedException;

    /** Removes and returns the oldest item, waiting while the queue is empty. Throws {@link QueueClosedException} if the queue is closed and empty. */
    E take() throws InterruptedException;

    /** Adds {@code e} only if there is room right now. Returns {@code false} when full, and never waits. */
    boolean offer(E e);

    /** Adds {@code e}, waiting at most the given time for room. Returns {@code false} if the time ran out. */
    boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException;

    /** Removes the oldest item if there is one right now, otherwise returns {@code null}. Never waits. */
    E poll();

    /** Removes the oldest item, waiting at most the given time for one. Returns {@code null} if the time ran out. */
    E poll(long timeout, TimeUnit unit) throws InterruptedException;

    /** Returns how many items are inside right now. It is a snapshot, so it may be stale a moment later. */
    int size();

    /** Returns the fixed capacity chosen at construction. */
    int capacity();

    /** Closes the queue and wakes every waiting thread so it can notice. Calling it twice does nothing the second time. */
    void close();

    /** Returns whether {@link #close()} has been called. */
    boolean isClosed();

    /** Closes the queue and removes every item inside, oldest first, in ONE step under the lock. No taker can slip in between the close and the drain, so every returned item is guaranteed never to run. */
    List<E> closeAndDrain();

    /** Removes and returns every item inside, oldest first. Used by {@code shutdownNow} to hand back the work that never ran. */
    List<E> drainAll();
}
