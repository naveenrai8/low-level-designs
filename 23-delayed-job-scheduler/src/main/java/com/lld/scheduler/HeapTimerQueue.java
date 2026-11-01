package com.lld.scheduler;

import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * The simple queue: a binary min-heap ordered by (due time, sequence number). Add and poll cost O(log n).
 *
 * <p>Cancelling is lazy: a cancelled job stays in the heap as a "tombstone" and is thrown away when it reaches the top. If tombstones pile up (more than 1,024 and more than half the heap) the heap is rebuilt without them, so cancelled far-future jobs cannot leak memory forever.
 */
final class HeapTimerQueue implements TimerQueue {

    private static final int PURGE_MIN = 1024;

    private final PriorityQueue<ScheduledJob> heap = new PriorityQueue<>(Comparator.<ScheduledJob>comparingLong(j -> j.due).thenComparingLong(j -> j.seq));
    private int tombstones;

    /** Adds the job to the heap. */
    @Override
    public void add(ScheduledJob job) {
        job.queued = true;
        heap.add(job);
    }

    /** Counts a tombstone and rebuilds the heap without cancelled jobs when there are too many. */
    @Override
    public void remove(ScheduledJob job) {
        if (!job.queued) {
            return; // already polled: no dead entry was left behind, so nothing to count
        }
        tombstones++;
        if (tombstones > PURGE_MIN && tombstones > heap.size() / 2) {
            heap.removeIf(j -> {
                boolean dead = j.status.get() == JobStatus.CANCELLED;
                if (dead) {
                    j.queued = false;
                }
                return dead;
            });
            tombstones = 0;
        }
    }

    /** Returns the smallest (due, seq) job if it is due, otherwise null. */
    @Override
    public ScheduledJob pollDue(long now) {
        ScheduledJob top = heap.peek();
        if (top == null || top.due > now) {
            return null;
        }
        if (top.status.get() == JobStatus.CANCELLED && tombstones > 0) {
            tombstones--;
        }
        top.queued = false;
        return heap.poll();
    }

    /** Returns how many entries (live and tombstones) the heap holds, for tests. */
    int physicalSize() {
        return heap.size();
    }

    /** Returns the current tombstone counter, for tests. */
    int tombstones() {
        return tombstones;
    }
}
