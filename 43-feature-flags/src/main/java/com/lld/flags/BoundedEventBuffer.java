package com.lld.flags;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A fixed-size queue of events. When it is full, new events are dropped and counted, so a slow consumer can never slow evaluation down.
 * <p>We drop the newest event (the queue keeps what it already has) because that is cheapest and never needs a lock.
 */
public final class BoundedEventBuffer implements EventSink {
    private final ArrayBlockingQueue<EvaluationEvent> queue;
    private final AtomicLong dropped = new AtomicLong();

    /**
     * Creates a buffer.
     *
     * @param capacity the most events it holds at once (1 or more)
     */
    public BoundedEventBuffer(int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /**
     * Adds the event if there is room, otherwise counts a drop. Never blocks.
     *
     * @param event the event
     */
    @Override
    public void emit(EvaluationEvent event) {
        if (!queue.offer(event)) {
            dropped.incrementAndGet();
        }
    }

    /**
     * Removes and returns everything buffered, oldest first.
     *
     * @return the events, which may be empty
     */
    public List<EvaluationEvent> drain() {
        List<EvaluationEvent> out = new ArrayList<>();
        queue.drainTo(out);
        return out;
    }

    /**
     * Says how many events were thrown away because the buffer was full.
     *
     * @return the total drops since creation
     */
    public long dropped() {
        return dropped.get();
    }
}
