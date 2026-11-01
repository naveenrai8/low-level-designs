package com.lld.pubsub;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** The in-memory {@link PubSub}. It only routes calls to the right {@link Topic}; all the rules live in Topic and Subscription. */
public final class InMemoryPubSub implements PubSub {
    private final Ticker ticker;
    private final java.util.concurrent.atomic.AtomicLong tokens = new java.util.concurrent.atomic.AtomicLong();
    private final ConcurrentMap<String, Topic> topics = new ConcurrentHashMap<>();

    /**
     * Creates a broker.
     *
     * @param ticker the clock used for ack deadlines
     */
    public InMemoryPubSub(Ticker ticker) {
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    /** Test seam: wakes every consumer waiting on the topic so it re-checks deadlines. Tests call it after moving a fake ticker forward. */
    void wake(String topic) {
        Topic t = topic(topic);
        t.lock.lock();
        try {
            t.changed.signalAll();
        } finally {
            t.lock.unlock();
        }
    }

    /** Finds a topic or throws IllegalArgumentException. */
    private Topic topic(String name) {
        Topic t = topics.get(name);
        if (t == null) {
            throw new IllegalArgumentException("unknown topic: " + name);
        }
        return t;
    }

    /** Creates a topic with no lag limit. */
    @Override
    public void createTopic(String topic) {
        createTopic(topic, Integer.MAX_VALUE);
    }

    /** Creates a topic with a lag limit; fails if the name exists or the limit is below 1. */
    @Override
    public void createTopic(String topic, int maxLag) {
        if (maxLag < 1) {
            throw new IllegalArgumentException("maxLag must be >= 1");
        }
        if (topics.putIfAbsent(topic, new Topic(topic, maxLag)) != null) {
            throw new IllegalStateException("topic already exists: " + topic);
        }
    }

    /** Adds a group to the topic; fails if the group exists. */
    @Override
    public void subscribe(String topic, String group, SubscriptionConfig config) {
        topic(topic).subscribe(group, config, ticker, tokens);
    }

    /** Appends a message, or throws BackpressureException when a group is at the lag limit. */
    @Override
    public long publish(String topic, String key, String payload) {
        return topic(topic).publish(key, payload);
    }

    /** Returns the next deliverable message for the group, or empty. */
    @Override
    public Optional<Delivery> poll(String topic, String group) {
        Topic t = topic(topic);
        Subscription s = t.group(group);
        t.lock.lock();
        try {
            return Optional.ofNullable(s.pollLocked());
        } finally {
            t.lock.unlock();
        }
    }

    /** Waits until a message is deliverable for the group, then returns it. */
    @Override
    public Delivery take(String topic, String group) throws InterruptedException {
        Topic t = topic(topic);
        Subscription s = t.group(group);
        t.lock.lockInterruptibly();
        try {
            Delivery d = s.pollLocked();
            while (d == null) {
                // Sleep until a signal, or until the earliest ack deadline, whichever is first, so a quiet topic still redelivers.
                long wait = s.nanosUntilNextDeadlineLocked();
                if (wait < 0) {
                    t.changed.await();
                } else {
                    t.changed.awaitNanos(wait);
                }
                d = s.pollLocked();
            }
            return d;
        } finally {
            t.lock.unlock();
        }
    }

    /** Settles a delivery as success; false if the receipt is stale. */
    @Override
    public boolean ack(String topic, String group, Delivery delivery) {
        Topic t = topic(topic);
        Subscription s = t.group(group);
        return locked(t, () -> s.ackLocked(delivery));
    }

    /** Settles a delivery as failure; false if the receipt is stale. */
    @Override
    public boolean nack(String topic, String group, Delivery delivery) {
        Topic t = topic(topic);
        Subscription s = t.group(group);
        return locked(t, () -> s.nackLocked(delivery));
    }

    /** Moves only this group to the given offset. */
    @Override
    public void seek(String topic, String group, long offset) {
        Topic t = topic(topic);
        Subscription s = t.group(group);
        locked(t, () -> {
            s.seekLocked(offset);
            return true;
        });
    }

    /** Returns the group's unfinished message count. */
    @Override
    public int lag(String topic, String group) {
        Topic t = topic(topic);
        Subscription s = t.group(group);
        return lockedInt(t, s);
    }

    /** Returns the group's next-to-read offset. */
    @Override
    public long position(String topic, String group) {
        Topic t = topic(topic);
        Subscription s = t.group(group);
        ReentrantLock l = t.lock;
        l.lock();
        try {
            return s.positionLocked();
        } finally {
            l.unlock();
        }
    }

    /** Returns a copy of the group's DLQ. */
    @Override
    public List<DeadLetter> deadLetters(String topic, String group) {
        Topic t = topic(topic);
        Subscription s = t.group(group);
        t.lock.lock();
        try {
            return s.deadLocked();
        } finally {
            t.lock.unlock();
        }
    }

    /** Runs an action holding the topic lock. */
    private static boolean locked(Topic t, java.util.function.BooleanSupplier action) {
        t.lock.lock();
        try {
            return action.getAsBoolean();
        } finally {
            t.lock.unlock();
        }
    }

    /** Reads a group's lag holding the topic lock. */
    private static int lockedInt(Topic t, Subscription s) {
        t.lock.lock();
        try {
            return s.lagLocked();
        } finally {
            t.lock.unlock();
        }
    }
}
