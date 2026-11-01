package com.lld.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for the heap's tombstone bookkeeping, driving the queue directly. */
class HeapTimerQueueTest {

    /** Makes a job record with a due time and sequence number, as the scheduler would. */
    private static ScheduledJob job(long id, long due, long seq) {
        ScheduledJob j = new ScheduledJob(id, () -> { }, ScheduledJob.Mode.ONCE, 0, null, null, null);
        j.due = due;
        j.seq = seq;
        return j;
    }

    /**
     * Proves a purge keeps every live job and keeps them in (due, seq) order. Live jobs (including several with equal due times) are mixed with thousands of cancelled ones, so purges happen several times along the way.
     */
    @Test
    void purgeKeepsLiveJobsInOrder() {
        HeapTimerQueue q = new HeapTimerQueue();
        List<ScheduledJob> live = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            ScheduledJob j = job(i, 1000 + (i / 7) % 5, i);
            q.add(j);
            if (i % 30 == 0) {
                live.add(j);
            } else {
                j.status.set(JobStatus.CANCELLED);
                q.remove(j);
            }
        }
        assertTrue(q.physicalSize() < 3000, "at least one purge happened, size=" + q.physicalSize());

        List<ScheduledJob> drained = new ArrayList<>();
        ScheduledJob j;
        while ((j = q.pollDue(Long.MAX_VALUE)) != null) {
            if (j.status.get() != JobStatus.CANCELLED) {
                drained.add(j);
            }
        }

        List<ScheduledJob> expected = new ArrayList<>(live);
        expected.sort(Comparator.<ScheduledJob>comparingLong(x -> x.due).thenComparingLong(x -> x.seq));
        assertEquals(expected, drained);
    }

    /** Proves removing a job that has already left the heap (the scheduler polled it, then a cancel came in) is not counted as a tombstone, because no dead entry is left behind. */
    @Test
    void removingAnAlreadyPolledJobLeavesNoTombstone() {
        HeapTimerQueue q = new HeapTimerQueue();
        ScheduledJob j = job(1, 5, 0);
        q.add(j);
        assertEquals(j, q.pollDue(10));
        j.status.set(JobStatus.CANCELLED);

        q.remove(j);

        assertEquals(0, q.tombstones());
    }
}
