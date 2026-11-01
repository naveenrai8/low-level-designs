package com.lld.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import com.lld.scheduler.SchedulerContractTest.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Proves cancel racing the fire has exactly one winner. A test hook runs in the one-instruction gap between "the scheduler took the job out of the queue" and "the scheduler claims it to run", which is the worst place for a cancel to land.
 */
class CancelRaceTest {

    /** Proves a cancel that lands inside the gap wins: cancel returns true and the job never runs (same-thread probe, no timing involved). */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void cancelInsideTheGapMeansItNeverRuns(Kind kind) {
        FakeTicker t = new FakeTicker();
        AtomicBoolean ran = new AtomicBoolean();
        JobHandle[] h = new JobHandle[1];
        TimerScheduler s = kind.hooked(t, job -> assertTrue(job.cancel()), j -> { });
        h[0] = s.schedule(() -> ran.set(true), Duration.ofMillis(1));
        t.advanceMillis(1);

        assertEquals(0, s.runDue());

        assertFalse(ran.get());
        assertEquals(JobStatus.CANCELLED, h[0].status());
        assertEquals(0, s.pending());
    }

    /** Proves a cancel that arrives after the claim loses: the job runs, cancel returns false, and the status is DONE. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void cancelAfterTheClaimLosesAndTheJobRuns(Kind kind) {
        FakeTicker t = new FakeTicker();
        AtomicBoolean cancelResult = new AtomicBoolean(true);
        JobHandle[] h = new JobHandle[1];
        TimerScheduler s = kind.build(t);
        h[0] = s.schedule(() -> cancelResult.set(h[0].cancel()), Duration.ofMillis(1));
        t.advanceMillis(1);

        assertEquals(1, s.runDue());

        assertFalse(cancelResult.get(), "a one-shot that is already running cannot be cancelled");
        assertEquals(JobStatus.DONE, h[0].status());
    }

    /**
     * Barrier-aligned real-thread race: in each of up to 20,000 rounds a second thread is released from a barrier at the gap, so cancel and claim start at the same moment. Every round must end with exactly one outcome (ran XOR cancel returned true). The test also wants to see the cancel win at least once and the fire win at least once, so a scheduler that always lets one side win cannot pass. Which side wins is up to the CPU, so the loop stops as soon as both have been seen. The deterministic claims are the same-thread probes above.
     */
    @Test
    void barrierAlignedRaceHasExactlyOneWinnerEveryRound() throws Exception {
        int ranCount = 0;
        int cancelledCount = 0;
        for (int round = 0; round < 20_000 && (ranCount == 0 || cancelledCount == 0); round++) {
            FakeTicker t = new FakeTicker();
            CyclicBarrier gap = new CyclicBarrier(2);
            AtomicInteger runs = new AtomicInteger();
            AtomicBoolean cancelWon = new AtomicBoolean();
            TimerScheduler s = TimerScheduler.withHeapAndHook(t, job -> await(gap));
            JobHandle h = s.schedule(runs::incrementAndGet, Duration.ofMillis(1));
            t.advanceMillis(1);

            Thread canceller = new Thread(() -> {
                await(gap);
                cancelWon.set(h.cancel());
            });
            canceller.start();
            s.runDue();
            canceller.join();

            boolean ran = runs.get() == 1;
            assertTrue(ran ^ cancelWon.get(), "round " + round + ": ran=" + ran + " cancelWon=" + cancelWon.get());
            assertEquals(ran ? JobStatus.DONE : JobStatus.CANCELLED, h.status());
            if (ran) {
                ranCount++;
            } else {
                cancelledCount++;
            }
        }
        assertTrue(ranCount > 0 && cancelledCount > 0, "both sides should win at least once: ran=" + ranCount + " cancelled=" + cancelledCount);
    }

    /** Waits at the barrier; turns the checked exceptions into an unchecked one so it can be used inside lambdas. */
    private static void await(CyclicBarrier b) {
        try {
            b.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
