package com.lld.leaderboard;

import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Makes any {@link Leaderboard} safe to share between threads with one read-write lock.
 *
 * <p><b>Why one lock.</b> A score change touches the hash map and moves a node in the tree, and the two must change together or a reader could see a user twice or not at all. One lock around both is the simplest thing that is obviously correct. Each write holds it only for one O(log n) tree edit (our estimate: a few microseconds), so the lock is rarely the bottleneck before the network is.
 *
 * <p><b>Why a read-write lock.</b> Reads (rank, top) do not change the tree, so many readers can run at once. Writers take the lock alone.
 *
 * <p><b>Trade-off.</b> All writers queue on one lock, so write throughput does not grow with cores. Striping by user does not help, because a rank query needs a global order across all users. The next step is a single writer thread fed by a queue (batching updates), or sharding users across several boards and merging their top-K. See the README.
 *
 * <p>{@link #increment(String, long)} is atomic because the whole read-add-write happens under the write lock. Doing {@code score()} then {@code setScore()} from outside would lose updates.
 */
public final class ConcurrentLeaderboard implements Leaderboard {

    private final Leaderboard delegate;
    private final ReadWriteLock lock;

    /** Wraps {@code delegate}. After this call only use the wrapper, never the delegate directly. */
    public ConcurrentLeaderboard(Leaderboard delegate) {
        this(delegate, new ReentrantReadWriteLock());
    }

    /** Test seam: the test owns the lock, so it can ask "does this thread hold the write lock right now" and "is that thread waiting in the queue". */
    ConcurrentLeaderboard(Leaderboard delegate, ReentrantReadWriteLock lock) {
        this.delegate = delegate;
        this.lock = lock;
    }

    /** Sets the score; see {@link Leaderboard#setScore}. */
    @Override
    public void setScore(String user, long score) {
        lock.writeLock().lock();
        try {
            delegate.setScore(user, score);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Adds to the score and returns the new one; see {@link Leaderboard#increment}. */
    @Override
    public long increment(String user, long delta) {
        lock.writeLock().lock();
        try {
            return delegate.increment(user, delta);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Removes the user; see {@link Leaderboard#remove}. */
    @Override
    public boolean remove(String user) {
        lock.writeLock().lock();
        try {
            return delegate.remove(user);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Looks up a score; see {@link Leaderboard#score}. */
    @Override
    public OptionalLong score(String user) {
        lock.readLock().lock();
        try {
            return delegate.score(user);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Returns the unique 1-based position; see {@link Leaderboard#rank}. */
    @Override
    public OptionalInt rank(String user) {
        lock.readLock().lock();
        try {
            return delegate.rank(user);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Returns the shared "1224" rank; see {@link Leaderboard#competitionRank}. */
    @Override
    public OptionalInt competitionRank(String user) {
        lock.readLock().lock();
        try {
            return delegate.competitionRank(user);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Returns the best k rows; see {@link Leaderboard#top}. */
    @Override
    public List<Ranked> top(int k) {
        lock.readLock().lock();
        try {
            return delegate.top(k);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Returns rows by 1-based rank range; see {@link Leaderboard#range}. */
    @Override
    public List<Ranked> range(int fromRank, int toRank) {
        lock.readLock().lock();
        try {
            return delegate.range(fromRank, toRank);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Returns the number of users; see {@link Leaderboard#size}. */
    @Override
    public int size() {
        lock.readLock().lock();
        try {
            return delegate.size();
        } finally {
            lock.readLock().unlock();
        }
    }
}
