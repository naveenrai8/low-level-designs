package com.lld.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lld.scheduler.SchedulerContractTest.Kind;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Cancel corner cases on repeating jobs and retrying jobs: the places where a cancel meets a job that is in the middle of a run. */
class CancelEdgeTest {

    /** Waits on a latch, turning the checked exception into an unchecked one. */
    private static void await(CountDownLatch l) {
        try {
            l.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Proves a cancel that read RUNNING and then got parked while the run finished and the job was re-queued (status back to SCHEDULED) still wins: it notices the new status, retries, and returns true. Nothing runs afterwards.
     *
     * <p>Forced with a hook that parks the canceller right after it reads the status. The driver finishes the run and re-queues, then the canceller is released.
     */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void cancelParkedMidRunStillWinsAfterTheJobIsRequeued(Kind kind) throws Exception {
        FakeTicker t = new FakeTicker();
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean parked = new AtomicBoolean();
        TimerScheduler s = kind.hooked(t, j -> { }, j -> {
            if (parked.compareAndSet(false, true)) {
                reached.countDown();
                await(release);
            }
        });
        AtomicInteger runs = new AtomicInteger();
        AtomicBoolean cancelResult = new AtomicBoolean();
        JobHandle[] h = new JobHandle[1];
        Thread[] canceller = new Thread[1];
        h[0] = s.scheduleAtFixedRate(() -> {
            if (runs.incrementAndGet() == 1) {
                canceller[0] = new Thread(() -> cancelResult.set(h[0].cancel()));
                canceller[0].start();
                await(reached);
            }
        }, Duration.ZERO, Duration.ofMillis(10));

        s.runDue();
        release.countDown();
        canceller[0].join();

        assertTrue(cancelResult.get(), "cancel must report true: the job really is stopped");
        t.advanceMillis(1000);
        s.runDue();
        assertEquals(1, runs.get());
        assertEquals(JobStatus.CANCELLED, h[0].status());
        assertEquals(0, s.pending());
    }

    /** Proves a retrying one-shot can be cancelled from inside an attempt: cancel returns true, and the failed attempt does not schedule a retry. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void cancelDuringARetryAttemptStopsTheRetries(Kind kind) {
        FakeTicker t = new FakeTicker();
        TimerScheduler s = kind.build(t);
        AtomicInteger runs = new AtomicInteger();
        AtomicBoolean cancelResult = new AtomicBoolean();
        JobHandle[] h = new JobHandle[1];
        h[0] = s.scheduleWithRetry(() -> {
            runs.incrementAndGet();
            cancelResult.set(h[0].cancel());
            throw new IllegalStateException("x");
        }, Duration.ZERO, new RetryPolicy(5, Duration.ofMillis(10), Duration.ofMillis(10), new Random(1)));

        s.runDue();
        t.advanceMillis(1000);
        s.runDue();

        assertTrue(cancelResult.get());
        assertEquals(1, runs.get());
        assertEquals(JobStatus.CANCELLED, h[0].status());
        assertEquals(0, s.pending());
    }

    /** Proves the cancelled status is not overwritten with DONE when the cancelled attempt was also the last allowed attempt. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void cancelledStatusSurvivesTheLastFailedAttempt(Kind kind) {
        FakeTicker t = new FakeTicker();
        TimerScheduler s = kind.build(t);
        JobHandle[] h = new JobHandle[1];
        h[0] = s.scheduleWithRetry(() -> {
            h[0].cancel();
            throw new IllegalStateException("x");
        }, Duration.ZERO, new RetryPolicy(1, Duration.ofMillis(10), Duration.ofMillis(10), new Random(1)));

        s.runDue();

        assertEquals(JobStatus.CANCELLED, h[0].status());
    }
}
