package com.lld.pubsub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An append-only message log plus its subscriber groups, all guarded by one lock.
 *
 * <p>One lock per topic keeps the invariants simple: a publish can check every group's lag and append in one step, and a group's poll can read the log without a second lock (so no lock-ordering risk). The cost is that groups of the same topic share that lock. Handlers always run outside it.
 */
final class Topic {
    final ReentrantLock lock = new ReentrantLock();
    final Condition changed = lock.newCondition();
    private final String name;
    private final int maxLag;
    private final List<Message> log = new ArrayList<>();
    private final Map<String, Subscription> groups = new LinkedHashMap<>();

    /**
     * Creates an empty topic.
     *
     * @param name   the topic name, used in error messages
     * @param maxLag the lag at which publish fails
     */
    Topic(String name, int maxLag) {
        this.name = name;
        this.maxLag = maxLag;
    }

    /** Appends a message, or throws {@link BackpressureException} if some group is at the lag limit. Wakes waiting consumers. */
    long publish(String key, String payload) {
        lock.lock();
        try {
            for (Map.Entry<String, Subscription> e : groups.entrySet()) {
                int lag = e.getValue().lagLocked();
                if (lag >= maxLag) {
                    throw new BackpressureException("group '" + e.getKey() + "' on topic '" + name + "' has lag " + lag + " (limit " + maxLag + ")");
                }
            }
            long offset = log.size();
            log.add(new Message(offset, key, payload));
            changed.signalAll();
            return offset;
        } finally {
            lock.unlock();
        }
    }

    /** Registers a group; fails if the name is taken. A LATEST group starts at the current end of the log. */
    void subscribe(String group, SubscriptionConfig config, Ticker ticker, java.util.concurrent.atomic.AtomicLong tokens) {
        lock.lock();
        try {
            if (groups.containsKey(group)) {
                throw new IllegalStateException("group already subscribed: " + group);
            }
            long start = config.startAt() == StartAt.EARLIEST ? 0 : log.size();
            groups.put(group, new Subscription(this, config, ticker, start, tokens));
        } finally {
            lock.unlock();
        }
    }

    /** Looks a group up, or throws IllegalArgumentException. */
    Subscription group(String group) {
        lock.lock();
        try {
            Subscription s = groups.get(group);
            if (s == null) {
                throw new IllegalArgumentException("unknown group '" + group + "' on topic '" + name + "'");
            }
            return s;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the message at an offset. The caller must hold the lock. */
    Message atLocked(long offset) {
        return log.get((int) offset);
    }

    /** Returns the log size. The caller must hold the lock. */
    long sizeLocked() {
        return log.size();
    }
}
