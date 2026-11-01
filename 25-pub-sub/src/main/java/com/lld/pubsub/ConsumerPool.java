package com.lld.pubsub;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A fixed set of worker threads that drain one subscriber group: take, run the handler, then ack (or nack if the handler fails).
 *
 * <p>The ordering guarantee does not live here. It comes from the group: a key with a message in flight is never handed to a second worker, so workers can be added freely and same-key messages still run one at a time, in publish order.
 *
 * <p>Failure rules: every delivery is settled. A handler that throws any {@link Exception} or {@link Error} (except a VM-level error such as out-of-memory) is nacked and the worker keeps running, so one bad message can never strand its key. A {@link VirtualMachineError} is nacked and then rethrown, ending that worker. If the handler is interrupted (shutdown), the message is nacked and the worker exits; that costs one attempt, which is the price of a simple broker without a "release" call.
 *
 * <p>"Processed but dead-lettered": if a handler finishes after its ack deadline, the broker has already given up on that delivery, the ack is refused, and the message may run again. Refused acks are counted in {@link #refusedAcks()}.
 */
public final class ConsumerPool implements AutoCloseable {
    private final PubSub broker;
    private final String topic;
    private final String group;
    private final MessageHandler handler;
    private final List<Thread> workers = new ArrayList<>();
    private final AtomicLong refused = new AtomicLong();
    private volatile boolean closed;

    /**
     * Starts the workers immediately.
     *
     * @param broker  the broker to read from
     * @param topic   the topic name
     * @param group   the group to drain
     * @param handler the processing code; must be idempotent because delivery is at-least-once
     * @param threads how many workers to run in parallel; must be at least 1
     * @throws IllegalArgumentException if threads is below 1 or the topic or group does not exist
     */
    public ConsumerPool(PubSub broker, String topic, String group, MessageHandler handler, int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1");
        }
        // Throws IllegalArgumentException for an unknown topic or group, before any thread starts.
        broker.position(topic, group);
        this.broker = broker;
        this.topic = topic;
        this.group = group;
        this.handler = handler;
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(this::run, "pubsub-" + group + "-" + i);
            workers.add(t);
            t.start();
        }
    }

    /** Returns how many acks the broker refused because the delivery had already expired or been forgotten. A non-zero value means some handler ran too slowly. */
    public long refusedAcks() {
        return refused.get();
    }

    /** The worker loop: runs until the pool is closed or the thread is interrupted. */
    private void run() {
        try {
            while (!closed) {
                Delivery d = broker.take(topic, group);
                process(d);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Runs the handler for one delivery and always settles it: ack on success, nack otherwise. */
    private void process(Delivery d) {
        boolean acked = false;
        boolean stop = false;
        Throwable fatal = null;
        try {
            handler.handle(d.message());
            if (!broker.ack(topic, group, d)) {
                refused.incrementAndGet();
            }
            acked = true;
        } catch (InterruptedException e) {
            // Shutdown interrupted the handler: keep the flag so the loop exits, and let the finally block nack.
            Thread.currentThread().interrupt();
            stop = true;
        } catch (VirtualMachineError e) {
            fatal = e;
        } catch (Exception | Error e) {
            // A failing handler is a nack, not a dead worker.
        } finally {
            if (!acked) {
                broker.nack(topic, group, d);
            }
        }
        if (fatal != null) {
            throw (VirtualMachineError) fatal;
        }
        if (stop) {
            closed = true;
        }
    }

    /** Stops the workers and waits for them to finish. A handler running right now is interrupted and its message is nacked. */
    @Override
    public void close() {
        closed = true;
        for (Thread t : workers) {
            t.interrupt();
        }
        for (Thread t : workers) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
