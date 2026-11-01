package com.lld.connpool;

import java.time.Duration;
import java.util.List;

/**
 * A bounded set of reusable connections.
 *
 * <p>Error contract: {@code borrow} throws {@link PoolTimeoutException} when nothing frees up in time, {@link ConnectionException} when a needed new connection cannot be opened, and {@link InterruptedException} when the waiting thread is interrupted (in all three cases no slot is lost). {@code release} and {@code invalidate} throw {@link IllegalStateException} for a connection that is not currently borrowed from this pool (a double return, a stale handle or a stranger).
 */
public interface ConnectionPool extends AutoCloseable {

    /**
     * Hands out a checked connection, waiting up to {@code timeout} for one.
     *
     * <p>The result is a per-loan handle: returning it switches it off, so a stale copy cannot be returned again or used.
     *
     * <p>Order of attempts: reuse an idle one (after validating it), else open a new one if there is room, else wait for a return.
     *
     * @throws IllegalArgumentException if the timeout is negative
     * @throws IllegalStateException    if the pool is closed
     */
    Connection borrow(Duration timeout) throws InterruptedException;

    /** Gives a borrowed connection back for reuse. Rejects a connection that is not currently on loan from this pool. */
    void release(Connection connection);

    /** Gives back a connection the caller knows is broken. It is closed, not reused, and its slot is freed. */
    void invalidate(Connection connection);

    /** Opens connections until {@code min} exist, so the first callers do not pay the opening cost. A failure frees its slot and is rethrown. */
    void warmUp();

    /** Closes idle connections that sat unused longer than {@code maxIdle}, but never below {@code min} and never a borrowed one. Returns how many were closed. */
    int evictIdle();

    /** Lists connections held longer than the leak threshold. It only reports, it never takes a connection back. */
    List<Leak> findLeaks();

    /** Returns a consistent snapshot of the counters. */
    Stats stats();

    /** Closes all idle connections and rejects new borrows. Borrowed connections are closed when they come back. */
    @Override
    void close();
}
