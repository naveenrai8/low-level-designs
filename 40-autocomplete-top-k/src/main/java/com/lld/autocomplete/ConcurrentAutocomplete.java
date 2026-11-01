package com.lld.autocomplete;

import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Makes any {@link Autocomplete} safe to share between threads with a read-write lock.
 *
 * <p>Why a lock and not a copy-on-write snapshot: one write touches every node on the word's path (and rebuilds lists on the way back), so a snapshot would copy L nodes with their maps and lists per write, and typing traffic is read-heavy but writes are also frequent. A read-write lock lets many suggest calls run together, and a write waits for them and then runs alone, so a reader can never see a half-updated path. Readers wait during a write; if that wait matters, switch to an immutable trie published through a volatile field. The lock primitives are explained in internal-pocs/Java-poc/concurrency/18-readwrite-and-stampedlock.
 */
public final class ConcurrentAutocomplete implements Autocomplete {

    private final Autocomplete delegate;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Runnable insideWrite;

    /** Wraps {@code delegate}. {@code insideWrite} runs while the write lock is held, right after the change; tests use it to freeze a writer mid-update. */
    public ConcurrentAutocomplete(Autocomplete delegate, Runnable insideWrite) {
        this.delegate = delegate;
        this.insideWrite = insideWrite;
    }

    /** Wraps {@code delegate} with no test hook. */
    public ConcurrentAutocomplete(Autocomplete delegate) {
        this(delegate, () -> { });
    }

    /** Returns true if the given thread is parked in this lock's wait queue. Lets tests prove a reader is really blocked by the lock, not just slow. */
    boolean isQueued(Thread t) {
        return lock.hasQueuedThread(t);
    }

    /** {@inheritDoc} */
    @Override
    public void add(String word, long delta) {
        lock.writeLock().lock();
        try {
            delegate.add(word, delta);
            insideWrite.run();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean remove(String word) {
        lock.writeLock().lock();
        try {
            boolean removed = delegate.remove(word);
            insideWrite.run();
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public long count(String word) {
        lock.readLock().lock();
        try {
            return delegate.count(word);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<Suggestion> suggest(String prefix, int k) {
        lock.readLock().lock();
        try {
            return delegate.suggest(prefix, k);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<Suggestion> suggestFuzzy(String prefix, int k) {
        lock.readLock().lock();
        try {
            return delegate.suggestFuzzy(prefix, k);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** {@inheritDoc} */
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
