package com.lld.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Behaviour every scheduler must show, run against both the heap and the timing wheel (1 ms tick). All time is virtual: the tests move a {@link FakeTicker} and call {@code runDue()}.
 */
class SchedulerContractTest {

    /** The two storage choices. The wheel uses a 1 ms tick, and every test here uses whole milliseconds, so both must behave the same. */
    enum Kind {
        /** The binary-heap queue. */
        HEAP(t -> new HeapTimerQueue()),
        WHEEL(t -> new TimingWheel(t.read(), 1_000_000L, 64));

        private final Function<Ticker, TimerQueue> queue;

        /** Remembers how to build a queue of this kind. */
        Kind(Function<Ticker, TimerQueue> queue) {
            this.queue = queue;
        }

        /** Builds a scheduler of this kind on the given ticker. */
        TimerScheduler build(Ticker t) {
            return hooked(t, j -> { }, j -> { });
        }

        /** Builds a scheduler of this kind with the two test hooks (after the queue gives a job up, and inside cancel before its status change). */
        TimerScheduler hooked(Ticker t, Consumer<JobHandle> afterPoll, Consumer<JobHandle> beforeCancelCas) {
            return TimerScheduler.forTest(t, queue.apply(t), afterPoll, beforeCancelCas);
        }
    }

    /** Shorthand for a number of milliseconds as a Duration. */
    private static Duration ms(long n) {
        return Duration.ofMillis(n);
    }

    /** Proves a delayed job does not run before its time and runs exactly once at its time. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void jobRunsOnceAtItsDueTimeAndNotBefore(Kind kind) {
        FakeTicker t = new FakeTicker();
        TimerScheduler s = kind.build(t);
        List<Long> ranAt = new ArrayList<>();
        JobHandle h = s.schedule(() -> ranAt.add(t.elapsedMillis()), ms(100));

        t.advanceMillis(99);
        assertEquals(0, s.runDue());
        t.advanceMillis(1);
        assertEquals(1, s.runDue());
        t.advanceMillis(500);
        assertEquals(0, s.runDue());

        assertEquals(List.of(100L), ranAt);
        assertEquals(JobStatus.DONE, h.status());
        assertEquals(1, h.attempts());
    }

    /** Proves scheduleAt takes an absolute ticker reading and that a time in the past means "run at the next runDue". */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void scheduleAtAcceptsAbsoluteAndPastTimes(Kind kind) {
        FakeTicker t = new FakeTicker();
        TimerScheduler s = kind.build(t);
        List<String> log = new ArrayList<>();
        s.scheduleAt(() -> log.add("future"), t.read() + 5_000_000L);
        s.scheduleAt(() -> log.add("past"), t.read() - 5_000_000L);

        s.runDue();
        assertEquals(List.of("past"), log);
        t.advanceMillis(5);
        s.runDue();
        assertEquals(List.of("past", "future"), log);
    }

    /** Proves bad arguments are rejected when scheduling, not later when the job is due. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void badArgumentsAreRejectedUpFront(Kind kind) {
        TimerScheduler s = kind.build(new FakeTicker());
        assertThrows(IllegalArgumentException.class, () -> s.schedule(() -> { }, ms(-1)));
        assertThrows(IllegalArgumentException.class, () -> s.scheduleAtFixedRate(() -> { }, ms(0), ms(0)));
        assertThrows(IllegalArgumentException.class, () -> s.scheduleWithFixedDelay(() -> { }, ms(0), ms(-5)));
        assertThrows(NullPointerException.class, () -> s.schedule(null, ms(1)));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, ms(1), ms(2), new Random(1)));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(3, ms(10), ms(5), new Random(1)));
    }

    /** Proves jobs due at the very same instant run in the order they were scheduled (the sequence tie-breaker), even when scheduled in a "backwards" order of delay. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void sameInstantJobsRunInSchedulingOrder(Kind kind) {
        FakeTicker t = new FakeTicker();
        TimerScheduler s = kind.build(t);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            int id = i;
            s.schedule(() -> order.add(id), ms(10));
        }
        s.schedule(() -> order.add(-1), ms(5));

        t.advanceMillis(10);
        s.runDue();

        List<Integer> expected = new ArrayList<>(List.of(-1));
        for (int i = 0; i < 50; i++) {
            expected.add(i);
        }
        assertEquals(expected, order);
    }

    /** Proves different due times run earliest first, no matter the order they were scheduled. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void earlierDueTimeRunsFirst(Kind kind) {
        FakeTicker t = new FakeTicker();
        TimerScheduler s = kind.build(t);
        List<String> order = new ArrayList<>();
        s.schedule(() -> order.add("c"), ms(30));
        s.schedule(() -> order.add("a"), ms(10));
        s.schedule(() -> order.add("b"), ms(20));
        t.advanceMillis(100);
        s.runDue();
        assertEquals(List.of("a", "b", "c"), order);
    }

    /** Proves a job cancelled before its time never runs, cancel reports true once, and the pending count drops. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void cancelBeforeFireMeansNeverRuns(Kind kind) {
        FakeTicker t = new FakeTicker();
        TimerScheduler s = kind.build(t);
        List<String> log = new ArrayList<>();
        JobHandle h = s.schedule(() -> log.add("ran"), ms(10));
        s.schedule(() -> log.add("other"), ms(10));
        assertEquals(2, s.pending());

        assertTrue(h.cancel());
        assertFalse(h.cancel());
        assertEquals(1, s.pending());
        t.advanceMillis(10);
        s.runDue();

        assertEquals(List.of("other"), log);
        assertEquals(JobStatus.CANCELLED, h.status());
        assertEquals(0, h.attempts());
    }

    /** Proves cancelling a job that already ran returns false and changes nothing. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void cancelAfterRunReturnsFalse(Kind kind) {
        FakeTicker t = new FakeTicker();
        TimerScheduler s = kind.build(t);
        JobHandle h = s.schedule(() -> { }, ms(1));
        t.advanceMillis(1);
        s.runDue();
        assertFalse(h.cancel());
        assertEquals(JobStatus.DONE, h.status());
    }

    /** Proves a job that throws is recorded as a failure, and neither the scheduler nor the other jobs due at the same time are affected. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void throwingJobDoesNotStopTheScheduler(Kind kind) {
        FakeTicker t = new FakeTicker();
        TimerScheduler s = kind.build(t);
        List<String> log = new ArrayList<>();
        s.schedule(() -> log.add("before"), ms(5));
        JobHandle bad = s.schedule(() -> {
            throw new IllegalStateException("boom");
        }, ms(5));
        s.schedule(() -> {
            throw new AssertionError("even an Error");
        }, ms(5));
        s.schedule(() -> log.add("after"), ms(5));
        t.advanceMillis(5);

        assertEquals(4, s.runDue());

        assertEquals(List.of("before", "after"), log);
        assertEquals(2, s.deadLetters().size());
        assertEquals(bad.id(), s.deadLetters().get(0).jobId());
        assertEquals("boom", s.deadLetters().get(0).error().getMessage());
        s.schedule(() -> log.add("still alive"), ms(1));
        t.advanceMillis(1);
        s.runDue();
        assertEquals("still alive", log.get(2));
    }

    /** Proves a job may schedule another job from inside its own body, and a zero-delay child runs in the same runDue call. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void jobCanScheduleMoreJobs(Kind kind) {
        FakeTicker t = new FakeTicker();
        TimerScheduler s = kind.build(t);
        List<String> log = new ArrayList<>();
        s.schedule(() -> {
            log.add("parent");
            s.schedule(() -> log.add("child"), ms(0));
        }, ms(1));
        t.advanceMillis(1);
        assertEquals(2, s.runDue());
        assertEquals(List.of("parent", "child"), log);
    }

    /** Proves the exact start times of the fixed-rate and fixed-delay jobs when each run takes 150 ms but the period is 100 ms. This is the timeline table in the README. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void fixedRateKeepsTheGridAndFixedDelaySlips(Kind kind) {
        List<Long> rate = driveSlowJob(kind, true, 150, MissedRunPolicy.CATCH_UP);
        List<Long> delay = driveSlowJob(kind, false, 150, MissedRunPolicy.CATCH_UP);

        // fixed-rate: the grid is 0, 100, 200, ...; each run starts the moment the previous (150 ms) one ends, so starts are 0,150,300,450 (slots 0,100,200,300 are all overdue and run back-to-back)
        assertEquals(List.of(0L, 150L, 300L, 450L, 600L), rate);
        // fixed-delay: next start = end of last run + 100, so the gap is 150 + 100 = 250
        assertEquals(List.of(0L, 250L, 500L, 750L, 1000L), delay);
    }

    /** Proves a fixed-rate job with a 30 ms body lands exactly on start + k x 100 ms, while a fixed-delay job with the same body starts every 130 ms (run time + delay), so it drifts 30 ms per run. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void fixedRateDoesNotDriftWhenRunsAreFast(Kind kind) {
        assertEquals(List.of(0L, 100L, 200L, 300L, 400L), driveSlowJob(kind, true, 30, MissedRunPolicy.CATCH_UP));
        assertEquals(List.of(0L, 130L, 260L, 390L, 520L), driveSlowJob(kind, false, 30, MissedRunPolicy.CATCH_UP));
    }

    /** Proves the two policies for missed fixed-rate runs: CATCH_UP runs every missed slot back-to-back, COALESCE runs one and jumps to the next future slot. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void missedFixedRateRunsCatchUpOrCoalesce(Kind kind) {
        FakeTicker t1 = new FakeTicker();
        TimerScheduler catchUp = kind.build(t1);
        int[] n1 = {0};
        catchUp.scheduleAtFixedRate(() -> n1[0]++, ms(0), ms(100), MissedRunPolicy.CATCH_UP);
        t1.advanceMillis(450); // the caller "stalled": slots at 0,100,200,300,400 are all due
        catchUp.runDue();
        assertEquals(5, n1[0]);

        FakeTicker t2 = new FakeTicker();
        TimerScheduler coalesce = kind.build(t2);
        List<Long> starts = new ArrayList<>();
        coalesce.scheduleAtFixedRate(() -> starts.add(t2.elapsedMillis()), ms(0), ms(100), MissedRunPolicy.COALESCE);
        t2.advanceMillis(450);
        coalesce.runDue();
        assertEquals(1, starts.size());
        t2.advanceMillis(49);
        coalesce.runDue();
        assertEquals(1, starts.size()); // next slot is 500, still 1 ms away
        t2.advanceMillis(1);
        coalesce.runDue();
        assertEquals(2, starts.size());
    }

    /** Proves COALESCE does not treat a slot that is due exactly when a run ends as "missed": with a 100 ms body on a 100 ms period the starts are 0, 100, 200, the same as CATCH_UP. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void coalesceKeepsASlotThatIsDueExactlyNow(Kind kind) {
        List<Long> coalesce = driveSlowJob(kind, true, 100, MissedRunPolicy.COALESCE);
        List<Long> catchUp = driveSlowJob(kind, true, 100, MissedRunPolicy.CATCH_UP);
        assertEquals(List.of(0L, 100L, 200L, 300L, 400L), catchUp);
        assertEquals(catchUp, coalesce);
    }

    /** Proves a periodic job keeps its original place in the same-instant order: it was scheduled first, so at a shared due time it runs before a one-shot scheduled later. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void requeuedPeriodicJobKeepsItsTieOrder(Kind kind) {
        FakeTicker t = new FakeTicker();
        TimerScheduler s = kind.build(t);
        List<String> log = new ArrayList<>();
        s.scheduleAtFixedRate(() -> log.add("periodic"), ms(0), ms(10));
        s.schedule(() -> log.add("one-shot"), ms(10));
        s.runDue();
        t.advanceMillis(10);
        s.runDue();
        assertEquals(List.of("periodic", "periodic", "one-shot"), log);
    }

    /** Proves a job that is interrupted (throws InterruptedException) is recorded as a failure and the thread's interrupt flag is restored for the caller. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void interruptedJobRestoresTheInterruptFlag(Kind kind) {
        FakeTicker t = new FakeTicker();
        TimerScheduler s = kind.build(t);
        s.schedule(() -> {
            throw new InterruptedException("stop");
        }, ms(0));
        try {
            s.runDue();
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, s.deadLetters().size());
        } finally {
            Thread.interrupted(); // clear the flag so it does not leak into other tests
        }
    }

    /** Proves the dead-letter list is capped at 1,000 entries (oldest dropped), so a repeating job that fails every run cannot grow memory forever. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void deadLetterListIsCapped(Kind kind) {
        FakeTicker t = new FakeTicker();
        TimerScheduler s = kind.build(t);
        int[] runs = {0};
        s.scheduleAtFixedRate(() -> {
            throw new IllegalStateException("run " + (++runs[0]));
        }, ms(0), ms(1));
        for (int i = 0; i < 1499; i++) {
            t.advanceMillis(1);
        }
        s.runDue();
        assertEquals(1500, runs[0]);
        assertEquals(1000, s.deadLetters().size());
        assertEquals("run 1500", s.deadLetters().get(999).error().getMessage());
        assertEquals("run 501", s.deadLetters().get(0).error().getMessage());
    }

    /** Proves cancelling a fixed-rate job between runs stops every future run. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void cancellingFixedRateStopsAllFutureRuns(Kind kind) {
        FakeTicker t = new FakeTicker();
        TimerScheduler s = kind.build(t);
        int[] runs = {0};
        JobHandle h = s.scheduleAtFixedRate(() -> runs[0]++, ms(0), ms(10));
        for (int i = 0; i < 35; i++) {
            s.runDue();
            t.advanceMillis(1);
        }
        assertEquals(4, runs[0]); // slots 0, 10, 20, 30
        assertTrue(h.cancel());
        for (int i = 0; i < 100; i++) {
            s.runDue();
            t.advanceMillis(1);
        }
        assertEquals(4, runs[0]);
        assertEquals(0, s.pending());
    }

    /** Proves a repeating job that cancels itself from inside its own run finishes that run but never starts another. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void repeatingJobCanCancelItselfMidRun(Kind kind) {
        FakeTicker t = new FakeTicker();
        TimerScheduler s = kind.build(t);
        int[] runs = {0};
        JobHandle[] self = new JobHandle[1];
        self[0] = s.scheduleAtFixedRate(() -> {
            runs[0]++;
            assertTrue(self[0].cancel());
        }, ms(0), ms(10));
        for (int i = 0; i < 50; i++) {
            s.runDue();
            t.advanceMillis(1);
        }
        assertEquals(1, runs[0]);
        assertEquals(JobStatus.CANCELLED, self[0].status());
        assertEquals(0, s.pending());
    }

    /** Proves a throwing run of a repeating job lands in the dead-letter list but the job keeps its schedule. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void failingRunDoesNotStopARepeatingJob(Kind kind) {
        FakeTicker t = new FakeTicker();
        TimerScheduler s = kind.build(t);
        int[] runs = {0};
        s.scheduleAtFixedRate(() -> {
            if (++runs[0] == 2) {
                throw new IllegalStateException("run 2 fails");
            }
        }, ms(0), ms(10));
        for (int i = 0; i < 45; i++) {
            s.runDue();
            t.advanceMillis(1);
        }
        assertEquals(5, runs[0]);
        assertEquals(1, s.deadLetters().size());
    }

    /** Proves the exact backoff delays for a seeded generator: 10 ms base, 80 ms cap, equal jitter, so attempt n waits between half and all of min(80, 10 x 2^(n-1)). */
    @Test
    void retryDelaysAreExactForASeed() {
        RetryPolicy p = new RetryPolicy(10, ms(10), ms(80), new Random(42));
        List<Long> delays = new ArrayList<>();
        for (int attempt = 1; attempt <= 7; attempt++) {
            delays.add(p.delayAfter(attempt).toMillis());
        }
        assertEquals(EXPECTED_DELAYS, delays);
        long[] backoff = {10, 20, 40, 80, 80, 80, 80};
        for (int i = 0; i < 7; i++) {
            assertTrue(delays.get(i) >= backoff[i] / 2 && delays.get(i) <= backoff[i], "attempt " + (i + 1) + " is within [half, full] of its backoff");
        }
        RetryPolicy again = new RetryPolicy(10, ms(10), ms(80), new Random(42));
        for (int attempt = 1; attempt <= 7; attempt++) {
            assertEquals(delays.get(attempt - 1), again.delayAfter(attempt).toMillis());
        }
    }

    private static final List<Long> EXPECTED_DELAYS = List.of(6L, 16L, 28L, 80L, 52L, 46L, 68L);

    /** Proves a failing job is retried at exactly the policy's delays (after the failure, on virtual time) and the third attempt succeeds, so nothing is dead-lettered. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void retryRunsAtBackoffTimesUntilItSucceeds(Kind kind) {
        FakeTicker t = new FakeTicker();
        TimerScheduler s = kind.build(t);
        RetryPolicy policy = new RetryPolicy(5, ms(10), ms(80), new Random(7));
        RetryPolicy mirror = new RetryPolicy(5, ms(10), ms(80), new Random(7));
        long d1 = mirror.delayAfter(1).toMillis();
        long d2 = mirror.delayAfter(2).toMillis();
        List<Long> starts = new ArrayList<>();
        JobHandle h = s.scheduleWithRetry(() -> {
            starts.add(t.elapsedMillis());
            if (starts.size() < 3) {
                throw new IllegalStateException("flaky");
            }
        }, ms(0), policy);
        for (int i = 0; i < 200; i++) {
            s.runDue();
            t.advanceMillis(1);
        }
        assertEquals(List.of(0L, d1, d1 + d2), starts);
        assertEquals(3, h.attempts());
        assertEquals(JobStatus.DONE, h.status());
        assertTrue(s.deadLetters().isEmpty());
    }

    /** Proves a job that always fails stops after maxAttempts and goes to the dead-letter list with the attempt count and the last error. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void retryStopsAfterMaxAttemptsAndDeadLetters(Kind kind) {
        FakeTicker t = new FakeTicker();
        TimerScheduler s = kind.build(t);
        int[] runs = {0};
        JobHandle h = s.scheduleWithRetry(() -> {
            throw new IllegalStateException("fail #" + (++runs[0]));
        }, ms(0), new RetryPolicy(4, ms(5), ms(20), new Random(1)));
        for (int i = 0; i < 500; i++) {
            s.runDue();
            t.advanceMillis(1);
        }
        assertEquals(4, runs[0]);
        assertEquals(1, s.deadLetters().size());
        FailedJob f = s.deadLetters().get(0);
        assertEquals(h.id(), f.jobId());
        assertEquals(4, f.attempts());
        assertEquals("fail #4", f.error().getMessage());
        assertEquals(0, s.pending());
    }

    /** Proves a cancel between two retry attempts stops the retries. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void cancelDuringBackoffStopsRetries(Kind kind) {
        FakeTicker t = new FakeTicker();
        TimerScheduler s = kind.build(t);
        int[] runs = {0};
        JobHandle h = s.scheduleWithRetry(() -> {
            runs[0]++;
            throw new IllegalStateException("x");
        }, ms(0), new RetryPolicy(10, ms(50), ms(50), new Random(3)));
        s.runDue();
        assertEquals(1, runs[0]);
        assertTrue(h.cancel());
        t.advanceMillis(1000);
        s.runDue();
        assertEquals(1, runs[0]);
        assertTrue(s.deadLetters().isEmpty());
    }

    /** Runs one job on a fixed-rate (or fixed-delay) schedule with period 100 ms and returns its first five start times in virtual milliseconds. The body advances the ticker by {@code runMillis}, which stands for "the run took that long". A catch-up burst inside one runDue call may start more than five, so the list is cut to five. */
    private static List<Long> driveSlowJob(Kind kind, boolean fixedRate, long runMillis, MissedRunPolicy missed) {
        FakeTicker t = new FakeTicker();
        TimerScheduler s = kind.build(t);
        List<Long> starts = new ArrayList<>();
        Job body = () -> {
            starts.add(t.elapsedMillis());
            t.advanceMillis(runMillis);
        };
        if (fixedRate) {
            s.scheduleAtFixedRate(body, ms(0), ms(100), missed);
        } else {
            s.scheduleWithFixedDelay(body, ms(0), ms(100));
        }
        while (starts.size() < 5) {
            if (s.runDue() == 0) {
                t.advanceMillis(1);
            }
        }
        return starts.subList(0, 5);
    }
}
