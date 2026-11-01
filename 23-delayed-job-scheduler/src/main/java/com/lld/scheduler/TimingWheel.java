package com.lld.scheduler;

import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * A hashed timing wheel (Varghese and Lauck, 1987): time is cut into fixed "ticks", and a circular array of buckets has one bucket per tick position. A job due at tick T goes in bucket {@code T mod wheelSize}. Adding and cancelling cost O(1) (a cancel unlinks the job from its bucket's list). Firing costs O(jobs in the bucket).
 *
 * <p>Rules this class defines:
 * <ul>
 * <li>A job's due time is rounded <em>up</em> to a whole tick, so it never fires early and can fire up to one tick late.</li>
 * <li>Jobs in the same tick fire in the order they were scheduled (sequence number), not by their exact nanosecond due time inside the tick. With due times on the tick grid this is identical to the heap's order.</li>
 * <li>A job further away than one full turn of the wheel shares a bucket with nearer jobs. It stays put because its stored due tick is checked when the bucket is visited. That check is the cost of "hashed": a bucket crowded with far jobs is scanned again on every turn.</li>
 * </ul>
 *
 * <p>Jobs that become due are moved into a small "ready" heap ordered by (tick, seq), which {@code pollDue} drains. That keeps the order exact even when a job schedules a zero-delay job in the middle of a run.
 */
final class TimingWheel implements TimerQueue {

    private final long tickNanos;
    private final int mask;
    private final ScheduledJob[] head;
    private final ScheduledJob[] tail;
    private final PriorityQueue<ScheduledJob> ready = new PriorityQueue<>(Comparator.<ScheduledJob>comparingLong(j -> j.tick).thenComparingLong(j -> j.seq));
    private long processed;
    private int bucketed;

    /**
     * Creates a wheel.
     *
     * @param startNanos the ticker reading now; ticks before it count as already processed
     * @param tickNanos the length of one tick; also the timing precision
     * @param wheelSize number of buckets; must be a power of two
     * @throws IllegalArgumentException if tickNanos is not positive or wheelSize is not a power of two
     */
    TimingWheel(long startNanos, long tickNanos, int wheelSize) {
        if (tickNanos <= 0 || wheelSize < 1 || Integer.bitCount(wheelSize) != 1) {
            throw new IllegalArgumentException("tick must be positive and wheelSize a power of two");
        }
        this.tickNanos = tickNanos;
        this.mask = wheelSize - 1;
        this.head = new ScheduledJob[wheelSize];
        this.tail = new ScheduledJob[wheelSize];
        this.processed = Math.floorDiv(startNanos, tickNanos);
    }

    /** Puts the job in its bucket, or straight into the ready heap if its tick has already been processed. */
    @Override
    public void add(ScheduledJob job) {
        job.tick = Math.ceilDiv(job.due, tickNanos);
        if (job.tick <= processed) {
            ready.add(job);
            return;
        }
        int b = (int) (job.tick & mask);
        job.prev = tail[b];
        job.next = null;
        if (tail[b] == null) {
            head[b] = job;
        } else {
            tail[b].next = job;
        }
        tail[b] = job;
        job.inBucket = true;
        bucketed++;
    }

    /** Unlinks the job from its bucket in O(1). A job already in the ready heap is left there and skipped later. */
    @Override
    public void remove(ScheduledJob job) {
        if (job.inBucket) {
            unlink(job);
        }
    }

    /** Moves the wheel forward to the tick containing {@code now}, then returns the next ready job (smallest tick, then sequence), or null. */
    @Override
    public ScheduledJob pollDue(long now) {
        long nowTick = Math.floorDiv(now, tickNanos);
        if (nowTick > processed) {
            advanceTo(nowTick);
        }
        return ready.poll();
    }

    /** Moves every job due at or before tick {@code target} into the ready heap. It visits at most one full turn of the wheel (every bucket once), so a huge jump in time stays cheap, and it does nothing at all when no job is waiting in a bucket. */
    private void advanceTo(long target) {
        long last = Math.min(target, processed + head.length);
        for (long t = processed + 1; t <= last && bucketed > 0; t++) {
            int b = (int) (t & mask);
            ScheduledJob j = head[b];
            while (j != null) {
                ScheduledJob following = j.next;
                if (j.tick <= target) {
                    unlink(j);
                    ready.add(j);
                }
                j = following;
            }
        }
        processed = target;
    }

    /** Removes the job from its bucket's doubly linked list. */
    private void unlink(ScheduledJob j) {
        int b = (int) (j.tick & mask);
        if (j.prev == null) {
            head[b] = j.next;
        } else {
            j.prev.next = j.next;
        }
        if (j.next == null) {
            tail[b] = j.prev;
        } else {
            j.next.prev = j.prev;
        }
        j.prev = null;
        j.next = null;
        j.inBucket = false;
        bucketed--;
    }

    /** Returns how many jobs sit in buckets (not yet ready), for tests. */
    int bucketedCount() {
        return bucketed;
    }
}
