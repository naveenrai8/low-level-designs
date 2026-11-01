package com.lld.connpool;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The pool: one lock, one condition, and a single counter ({@code total}) that is the only thing deciding whether a new connection may be opened.
 *
 * <p>Invariant: {@code total == idle + borrowed + opening + validating + closing} and {@code total <= max} at all times. The check "is there room?" and the reservation {@code total++} happen in the same locked step, so two borrowers can never both see "room for one more".
 *
 * <p>Each loan is a fresh handle ({@link Lease}), like a HikariCP proxy. Returning a handle switches it off, so a stale second return of an old handle cannot touch the connection a later borrower now holds.
 *
 * <p>Slow work (validating, opening, closing) is done <em>outside</em> the lock so one slow database call does not freeze every other borrower. While it runs, the connection still counts in {@code total}, which is what keeps the cap honest. A connection is closed <em>before</em> its slot is given back, so the live count never exceeds {@code max}, not even for a moment.
 */
public final class BoundedPool implements ConnectionPool {

    private final ConnectionFactory factory;
    private final PoolConfig config;
    private final Ticker ticker;
    private final Probe probe;
    private final long maxIdleNanos;
    private final long leakNanos;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    // Newest first: borrowers take the most recently used connection, so the oldest sink to the tail where eviction finds them.
    private final ArrayDeque<Idle> idle = new ArrayDeque<>();
    private final Map<Lease, Loan> loans = new IdentityHashMap<>();
    private int total;
    private int closing;
    private int waiting;
    private boolean closed;

    /** The handle a borrower holds. It wraps the real connection and dies when returned, so a stale copy can neither be returned again nor used. */
    private final class Lease implements Connection {
        private final Connection raw;
        private volatile boolean active = true;

        /** Wraps a real connection for one loan. */
        Lease(Connection raw) {
            this.raw = raw;
        }

        /** Throws if this handle was already returned. */
        private void check() {
            if (!active) {
                throw new IllegalStateException("connection handle was already returned to the pool");
            }
        }

        /** Returns the real connection's id; throws if this handle was already returned. */
        @Override
        public long id() {
            check();
            return raw.id();
        }

        /** Asks the real connection if it is alive; throws if this handle was already returned. */
        @Override
        public boolean isValid() {
            check();
            return raw.isValid();
        }

        /** Gives the handle back to the pool (so try-with-resources works); it does not close the real connection. */
        @Override
        public void close() {
            release(this);
        }
    }

    /** An idle connection and the time it became idle. */
    private record Idle(Connection connection, long since) {
    }

    /** A borrowed connection with who took it, when, and from where. */
    private record Loan(long at, String threadName, Throwable stack) {
    }

    /** Creates a pool with no test seams. */
    public BoundedPool(ConnectionFactory factory, PoolConfig config, Ticker ticker) {
        this(factory, config, ticker, Probe.NONE);
    }

    /** Creates a pool; the probe lets a test pause threads at chosen points. No connection is opened yet. */
    public BoundedPool(ConnectionFactory factory, PoolConfig config, Ticker ticker, Probe probe) {
        this.factory = Objects.requireNonNull(factory);
        this.config = Objects.requireNonNull(config);
        this.ticker = Objects.requireNonNull(ticker);
        this.probe = Objects.requireNonNull(probe);
        this.maxIdleNanos = config.maxIdle().toNanos();
        this.leakNanos = config.leakThreshold().toNanos();
    }

    /** Hands out a validated connection: reuse an idle one, else open one if there is room, else wait until a return, a freed slot or the deadline. On interrupt or timeout nothing is taken and no slot is lost. */
    @Override
    public Connection borrow(Duration timeout) throws InterruptedException {
        long timeoutNanos = timeout.toNanos();
        if (timeoutNanos < 0) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        long deadline = ticker.read() + timeoutNanos;
        while (true) {
            Idle candidate;
            lock.lockInterruptibly();
            try {
                while (true) {
                    if (closed) {
                        throw new IllegalStateException("pool is closed");
                    }
                    candidate = idle.pollFirst();
                    if (candidate != null) {
                        break;
                    }
                    if (total < config.max()) {
                        // Check and reserve in one locked step: this is what stops two threads both creating the last connection.
                        total++;
                        break;
                    }
                    long remaining = deadline - ticker.read();
                    if (remaining <= 0) {
                        throw new PoolTimeoutException("no connection free within " + timeout);
                    }
                    waiting++;
                    try {
                        probe.beforeWait();
                        available.awaitNanos(remaining);
                        probe.afterWake();
                    } catch (InterruptedException e) {
                        // We may have just been chosen by a signal; pass it on so the wake-up is not wasted on a thread that is leaving.
                        available.signal();
                        throw e;
                    } finally {
                        waiting--;
                    }
                }
            } finally {
                lock.unlock();
            }
            Connection connection = candidate == null ? openReserved() : validateOrDrop(candidate.connection());
            if (connection != null) {
                return lend(connection);
            }
            // The idle one was bad and has been closed. Check the budget before trying the next one, so many slow bad connections cannot add up past the deadline. Strictly past: a zero timeout may still replace one bad idle connection without waiting.
            if (ticker.read() - deadline > 0) {
                throw new PoolTimeoutException("no connection free within " + timeout);
            }
        }
    }

    /** Opens a connection in a slot that is already reserved; if opening fails the slot goes back and a waiter is woken. */
    private Connection openReserved() {
        boolean ok = false;
        try {
            probe.afterReserveBeforeCreate();
            Connection created = factory.create();
            if (created == null) {
                throw new ConnectionException("factory returned null");
            }
            ok = true;
            return created;
        } finally {
            if (!ok) {
                giveBackSlot();
            }
        }
    }

    /** Checks an idle connection. Returns it if healthy, otherwise closes it, frees its slot and returns null. A check that throws counts as "bad". */
    private Connection validateOrDrop(Connection connection) {
        boolean healthy = false;
        try {
            healthy = connection.isValid();
        } catch (RuntimeException e) {
            healthy = false;
        } finally {
            if (!healthy) {
                closeQuietly(connection);
                giveBackSlot();
            }
        }
        return healthy ? connection : null;
    }

    /** Records the loan (who, when, where) and returns the connection. If the pool closed meanwhile, closes it and refuses. */
    private Connection lend(Connection connection) {
        // Build the stack before taking the lock: it is the slowest part and needs no shared state.
        Throwable stack = config.captureStacks() ? new Throwable("borrowed here") : null;
        Lease lease = new Lease(connection);
        boolean shut;
        lock.lock();
        try {
            shut = closed;
            if (!shut) {
                loans.put(lease, new Loan(ticker.read(), Thread.currentThread().getName(), stack));
            }
        } finally {
            lock.unlock();
        }
        if (shut) {
            closeQuietly(connection);
            giveBackSlot();
            throw new IllegalStateException("pool is closed");
        }
        return lease;
    }

    /** Returns a connection to the idle list and wakes one waiter. Throws if the connection is not currently on loan (double return or stranger). */
    @Override
    public void release(Connection connection) {
        stash(takeLoan(connection));
    }

    /** Closes a borrowed connection the caller found broken and frees its slot so a waiter can open a fresh one. */
    @Override
    public void invalidate(Connection connection) {
        closeQuietly(takeLoan(connection));
        giveBackSlot();
    }

    /** Ends the loan and returns the real connection, or throws if the handle is not on loan (a stranger, a handle from another pool, or one already returned). Switches the handle off. */
    private Connection takeLoan(Connection connection) {
        Objects.requireNonNull(connection);
        lock.lock();
        try {
            if (!(connection instanceof Lease lease) || loans.remove(lease) == null) {
                throw new IllegalStateException("connection is not currently borrowed from this pool");
            }
            lease.active = false;
            return lease.raw;
        } finally {
            lock.unlock();
        }
    }

    /** Puts a connection into the idle list and wakes exactly one waiter; if the pool is closed it closes the connection instead. */
    private void stash(Connection connection) {
        boolean shut;
        lock.lock();
        try {
            shut = closed;
            if (!shut) {
                idle.addFirst(new Idle(connection, ticker.read()));
                available.signal();
            }
        } finally {
            lock.unlock();
        }
        if (shut) {
            closeQuietly(connection);
            giveBackSlot();
        }
    }

    /** Frees one reserved slot and wakes one waiter, who may now open a connection. Call it only after the connection is really closed. */
    private void giveBackSlot() {
        lock.lock();
        try {
            total--;
            available.signal();
        } finally {
            lock.unlock();
        }
    }

    /** Closes a connection and ignores any failure: a connection that will not close cleanly is dropped anyway. */
    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (RuntimeException e) {
            // Deliberately ignored: the pool has already decided to drop this connection.
        }
    }

    /** Opens connections until {@code min} exist; borrowed connections count toward {@code min}. A failed open gives its slot back and the exception propagates. */
    @Override
    public void warmUp() {
        while (true) {
            lock.lock();
            try {
                if (closed || total >= config.min()) {
                    return;
                }
                total++;
            } finally {
                lock.unlock();
            }
            stash(openReserved());
        }
    }

    /** Closes idle connections older than maxIdle, oldest first, never going below min and never touching a borrowed one. Closing happens outside the lock; the slots are freed after the close. */
    @Override
    public int evictIdle() {
        List<Connection> victims = new ArrayList<>();
        lock.lock();
        try {
            long now = ticker.read();
            // Only idle connections are candidates, so a borrowed one can never be closed here. Closing ones still count in total, hence the subtraction.
            while (!idle.isEmpty() && total - closing > config.min()) {
                Idle oldest = idle.peekLast();
                if (now - oldest.since() < maxIdleNanos) {
                    break;
                }
                idle.pollLast();
                closing++;
                victims.add(oldest.connection());
            }
        } finally {
            lock.unlock();
        }
        for (Connection victim : victims) {
            closeQuietly(victim);
        }
        if (!victims.isEmpty()) {
            lock.lock();
            try {
                total -= victims.size();
                closing -= victims.size();
                available.signalAll();
            } finally {
                lock.unlock();
            }
        }
        return victims.size();
    }

    /** Reports every loan older than the leak threshold, with thread name and borrow stack. A threshold of zero means detection is off (as in HikariCP). Read-only. */
    @Override
    public List<Leak> findLeaks() {
        List<Leak> leaks = new ArrayList<>();
        lock.lock();
        try {
            if (leakNanos == 0) {
                return leaks;
            }
            long now = ticker.read();
            for (Map.Entry<Lease, Loan> e : loans.entrySet()) {
                long held = now - e.getValue().at();
                if (held >= leakNanos) {
                    leaks.add(new Leak(e.getKey().raw.id(), held, e.getValue().threadName(), e.getValue().stack()));
                }
            }
        } finally {
            lock.unlock();
        }
        return leaks;
    }

    /** Returns a snapshot of the counters taken under the lock. */
    @Override
    public Stats stats() {
        lock.lock();
        try {
            return new Stats(idle.size(), loans.size(), total, waiting);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Test seam: wakes every waiter so it re-reads the ticker.
     *
     * <p>{@code awaitNanos} parks on the real clock, so moving a fake ticker does not wake anyone by itself. A test moves the fake ticker, calls this, and the woken waiters see the new time. With the real ticker the waits end by themselves.
     */
    public void onTimeAdvanced() {
        lock.lock();
        try {
            available.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Test seam: true when some thread is blocked trying to take the pool lock, so a test can tell a releaser has arrived. */
    boolean hasThreadsQueuedOnLock() {
        return lock.hasQueuedThreads();
    }

    /** Closes idle connections, rejects new borrows, wakes all waiters and closes borrowed connections as they come back. */
    @Override
    public void close() {
        List<Connection> toClose = new ArrayList<>();
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            for (Idle i : idle) {
                toClose.add(i.connection());
            }
            idle.clear();
            closing += toClose.size();
            available.signalAll();
        } finally {
            lock.unlock();
        }
        for (Connection c : toClose) {
            closeQuietly(c);
        }
        lock.lock();
        try {
            total -= toClose.size();
            closing -= toClose.size();
        } finally {
            lock.unlock();
        }
    }
}
