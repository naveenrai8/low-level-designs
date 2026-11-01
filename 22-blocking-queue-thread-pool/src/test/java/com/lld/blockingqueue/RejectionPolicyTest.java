package com.lld.blockingqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/**
 * Risk 4: each rejection policy, with exact counts.
 *
 * <p>Setup for every test: 1 worker, queue capacity 2. A task "blocker" holds the worker on a latch, then tasks 1 and 2 fill the queue, so task 3 is refused. Nothing sleeps: the worker signals when it has started the blocker, and the test releases it when ready.
 */
class RejectionPolicyTest {

    /** A pool wired up so the worker is busy and the queue is full, ready for one refused submit. */
    private static final class Rig {
        final List<String> ran = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch release = new CountDownLatch(1);
        final FixedThreadPool pool;

        /** Builds the pool and fills it: 1 running (blocker) + 2 queued. */
        Rig(RejectionPolicy policy) throws InterruptedException {
            pool = new FixedThreadPool(1, 2, policy, Ticker.system(), WaitLoopTest::daemon);
            CountDownLatch started = new CountDownLatch(1);
            pool.execute(() -> {
                started.countDown();
                await(release);
                ran.add("blocker");
            });
            Bounded.await(started, "the worker never started the blocker task");
            pool.execute(() -> ran.add("1"));
            pool.execute(() -> ran.add("2"));
        }

        /** Lets the blocker go, closes the pool gracefully, and waits for every worker to finish. */
        void finish() throws InterruptedException {
            release.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS), "the pool did not terminate");
        }
    }

    /** Waits on a latch, turning the checked exception into a runtime one so it fits in a Runnable. */
    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Proves abort throws to the caller and the refused task never runs: 3 tasks run, 1 refused. */
    @Test
    void abortThrowsAndTheTaskIsNeverRun() throws Exception {
        Rig rig = new Rig(RejectionPolicies.abort());
        int refused = 0;

        try {
            rig.pool.execute(() -> rig.ran.add("3"));
        } catch (RejectedExecutionException expected) {
            refused++;
        }
        rig.finish();

        assertEquals(1, refused);

        assertEquals(List.of("blocker", "1", "2"), rig.ran);
        assertEquals(3, rig.pool.completedTasks());
    }

    /** Proves caller-runs makes the submitting thread run the task itself (that is the backpressure): task 3 runs on the test thread before the blocker finishes. */
    @Test
    void callerRunsRunsTheTaskOnTheSubmittingThread() throws Exception {
        Rig rig = new Rig(RejectionPolicies.callerRuns());
        String[] thread = new String[1];

        rig.pool.execute(() -> {
            thread[0] = Thread.currentThread().getName();
            rig.ran.add("3");
        });

        assertEquals(Thread.currentThread().getName(), thread[0]);
        assertEquals(List.of("3"), rig.ran); // blocker, 1 and 2 have not finished yet
        rig.finish();
        assertEquals(List.of("3", "blocker", "1", "2"), rig.ran);
    }

    /** Proves discard drops the new task silently: exactly 3 tasks run and no exception is thrown. */
    @Test
    void discardDropsTheNewTask() throws Exception {
        Rig rig = new Rig(RejectionPolicies.discard());

        rig.pool.execute(() -> rig.ran.add("3"));
        rig.finish();

        assertEquals(List.of("blocker", "1", "2"), rig.ran);
    }

    /** Proves discard-oldest drops the longest-waiting queued task (task 1) and keeps the new one: tasks 2 and 3 run, 1 never does. */
    @Test
    void discardOldestDropsTheLongestWaitingQueuedTask() throws Exception {
        Rig rig = new Rig(RejectionPolicies.discardOldest());

        rig.pool.execute(() -> rig.ran.add("3"));
        rig.finish();

        assertEquals(List.of("blocker", "2", "3"), rig.ran);
    }

    /** Proves every policy refuses work after shutdown: abort throws, the others run nothing. */
    @Test
    void afterShutdownNoPolicyRunsTheTask() throws Exception {
        for (RejectionPolicy policy : List.of(RejectionPolicies.callerRuns(), RejectionPolicies.discard(), RejectionPolicies.discardOldest())) {
            FixedThreadPool pool = new FixedThreadPool(1, 1, policy, Ticker.system(), WaitLoopTest::daemon);
            pool.shutdown();
            List<String> ran = new ArrayList<>();
            pool.execute(() -> ran.add("late"));
            assertEquals(List.of(), ran);
        }
        FixedThreadPool pool = new FixedThreadPool(1, 1, RejectionPolicies.abort(), Ticker.system(), WaitLoopTest::daemon);
        pool.shutdown();
        assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> { }));
    }
}
