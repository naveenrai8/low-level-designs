package com.lld.pubsub;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One subscriber group's private state on a topic: its read position, its retry queue, its in-flight messages and its DLQ.
 *
 * <p>Invariants (all checked under the topic lock): (1) a message is in exactly one of: unread (offset at or after the cursor), waiting in {@code ready}, in {@code inFlight}, acked/forgotten, or in {@code dead}; so nothing is lost. (2) A message is delivered at most {@code maxAttempts} times. (3) For one key at most one message is in flight <em>as far as the broker knows</em>, and a key's messages are handed out in offset order, so same-key work is serial and ordered while different keys run in parallel. Caveat: once an ack deadline expires the broker frees the key, so a handler that is still running (a zombie) can overlap with the next message of that key; only idempotent handlers make that safe.
 *
 * <p>Cost: {@code pollLocked} walks {@code ready} under the topic lock, so with one hot key that parks many messages each poll is O(parked). Production fix: a queue per key.
 *
 * <p>Every method with the suffix {@code Locked} must be called with the topic lock held.
 */
final class Subscription {
    /** A delivery that has been handed out and not yet settled. */
    private record InFlight(Message message, int attempt, long token, long deadline) {
    }

    private final Topic topic;
    private final SubscriptionConfig config;
    private final Ticker ticker;
    private long cursor;
    private final AtomicLong tokens;
    // Messages already read from the log that wait for (re)delivery: offset -> attempts used so far.
    private final TreeMap<Long, Integer> ready = new TreeMap<>();
    private final Map<Long, InFlight> inFlight = new HashMap<>();
    // key -> offset of the message of that key currently in flight.
    private final Map<String, Long> busy = new HashMap<>();
    private final List<DeadLetter> dead = new ArrayList<>();

    /** Creates a group that starts reading at {@code start}. Tokens come from one broker-wide counter, so a receipt can never match a delivery of another group or topic. */
    Subscription(Topic topic, SubscriptionConfig config, Ticker ticker, long start, AtomicLong tokens) {
        this.tokens = tokens;
        this.topic = topic;
        this.config = config;
        this.ticker = ticker;
        this.cursor = start;
    }

    /** Counts unread + waiting + in-flight messages. */
    int lagLocked() {
        expireLocked();
        return (int) (topic.sizeLocked() - cursor) + ready.size() + inFlight.size();
    }

    /** Returns the next-to-read offset. */
    long positionLocked() {
        expireLocked();
        return cursor;
    }

    /** Returns a copy of the DLQ. */
    List<DeadLetter> deadLocked() {
        expireLocked();
        return List.copyOf(dead);
    }

    /**
     * Picks the next message this group may hand out, or null.
     *
     * <p>Order of preference: the lowest-offset waiting message whose key is idle, then fresh messages from the log. A fresh message whose key is busy is parked in {@code ready} (that keeps its place in line) and the scan continues, so one slow key never blocks other keys.
     */
    Delivery pollLocked() {
        expireLocked();
        for (Map.Entry<Long, Integer> e : ready.entrySet()) {
            Message m = topic.atLocked(e.getKey());
            if (!busy.containsKey(m.key())) {
                int attemptsUsed = e.getValue();
                ready.remove(e.getKey());
                return handOut(m, attemptsUsed + 1);
            }
        }
        while (cursor < topic.sizeLocked()) {
            Message m = topic.atLocked(cursor++);
            if (busy.containsKey(m.key())) {
                ready.put(m.offset(), 0);
            } else {
                return handOut(m, 1);
            }
        }
        return null;
    }

    /** Records the message as in flight with a deadline and marks its key busy. */
    private Delivery handOut(Message m, int attempt) {
        long token = tokens.getAndIncrement();
        inFlight.put(m.offset(), new InFlight(m, attempt, token, ticker.read() + config.ackTimeoutNanos()));
        busy.put(m.key(), m.offset());
        return new Delivery(m, attempt, token);
    }

    /** Settles a delivery as success. Returns false if the receipt is stale. */
    boolean ackLocked(Delivery d) {
        expireLocked();
        InFlight f = inFlight.get(d.message().offset());
        if (f == null || f.token() != d.token()) {
            return false;
        }
        inFlight.remove(f.message().offset());
        // Take the key from the stored entry, never from the caller's receipt.
        busy.remove(f.message().key());
        topic.changed.signalAll();
        return true;
    }

    /** Settles a delivery as failure. Returns false if the receipt is stale. */
    boolean nackLocked(Delivery d) {
        expireLocked();
        InFlight f = inFlight.get(d.message().offset());
        if (f == null || f.token() != d.token()) {
            return false;
        }
        fail(f, "nack");
        topic.changed.signalAll();
        return true;
    }

    /** Returns how many nanoseconds until the earliest in-flight deadline (at least 1), or -1 if nothing is in flight. Lets a waiting consumer wake up exactly when a redelivery becomes due. */
    long nanosUntilNextDeadlineLocked() {
        long now = ticker.read();
        long best = -1;
        for (InFlight f : inFlight.values()) {
            long left = Math.max(1, f.deadline() - now);
            if (best < 0 || left < best) {
                best = left;
            }
        }
        return best;
    }

    /** Treats every in-flight message whose deadline has passed as a failed attempt. Runs at the start of every operation. */
    private void expireLocked() {
        long now = ticker.read();
        List<InFlight> late = new ArrayList<>();
        for (InFlight f : inFlight.values()) {
            // Subtraction form stays correct if the nanosecond counter wraps around.
            if (now - f.deadline() >= 0) {
                late.add(f);
            }
        }
        for (InFlight f : late) {
            fail(f, "ack deadline expired");
        }
        if (!late.isEmpty()) {
            topic.changed.signalAll();
        }
    }

    /** Frees the key, then either schedules a retry or moves the message to the DLQ if this was its last allowed attempt. */
    private void fail(InFlight f, String reason) {
        long offset = f.message().offset();
        inFlight.remove(offset);
        busy.remove(f.message().key());
        if (f.attempt() >= config.maxAttempts()) {
            // One DLQ entry per offset: a replay (seek back) that fails again replaces the old entry instead of duplicating it.
            dead.removeIf(x -> x.message().offset() == offset);
            dead.add(new DeadLetter(f.message(), f.attempt(), reason));
        } else {
            ready.put(offset, f.attempt());
        }
    }

    /** Moves the read position and forgets pending and in-flight work; the DLQ is kept. */
    void seekLocked(long offset) {
        // Settle overdue deliveries first, so an expired last attempt reaches the DLQ instead of being forgotten.
        expireLocked();
        if (offset < 0 || offset > topic.sizeLocked()) {
            throw new IllegalArgumentException("offset out of range: " + offset);
        }
        ready.clear();
        inFlight.clear();
        busy.clear();
        cursor = offset;
        topic.changed.signalAll();
    }
}
