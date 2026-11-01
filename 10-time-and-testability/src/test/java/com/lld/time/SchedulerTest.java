package com.lld.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Proves the virtual-time scheduler behaves like a real one (order, due times, cancel), plus one smoke test of the real executor scheduler.
 */
class SchedulerTest {

    private static final Instant T0 = Instant.parse("2026-03-08T09:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final ManualScheduler scheduler = new ManualScheduler(clock);

    /**
     * Proves tasks run earliest first, tied tasks run in the order they were scheduled, and a task due exactly at the end of the window still runs.
     */
    @Test
    void manualSchedulerRunsTasksInDueOrderWithTiesInSchedulingOrder() {
        List<String> ran = new ArrayList<>();
        scheduler.schedule(Duration.ofSeconds(3), () -> ran.add("c@3"));
        scheduler.schedule(Duration.ofSeconds(1), () -> ran.add("a@1"));
        scheduler.schedule(Duration.ofSeconds(1), () -> ran.add("b@1"));

        scheduler.advance(Duration.ofSeconds(2));
        assertEquals(List.of("a@1", "b@1"), ran);
        scheduler.advance(Duration.ofSeconds(1));      // a task due exactly at the window end runs
        assertEquals(List.of("a@1", "b@1", "c@3"), ran);
    }

    /**
     * Proves the clock reads each task's own due time while it runs, and ends at the end of the window.
     */
    @Test
    void manualSchedulerShowsEachTaskItsOwnDueTime() {
        List<Instant> seen = new ArrayList<>();
        scheduler.schedule(Duration.ofSeconds(5), () -> seen.add(clock.instant()));
        scheduler.schedule(Duration.ofSeconds(8), () -> seen.add(clock.instant()));

        scheduler.advance(Duration.ofMinutes(1));
        assertEquals(List.of(T0.plusSeconds(5), T0.plusSeconds(8)), seen);
        assertEquals(T0.plusSeconds(60), clock.instant());
    }

    /**
     * Proves a task that reschedules itself every 10 seconds runs three times in a 35-second window, with the next run still pending.
     */
    @Test
    void manualSchedulerRunsTasksScheduledByTasksInsideTheWindow() {
        List<Instant> seen = new ArrayList<>();
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {                               // a self-rescheduling sweep, every 10 s
            seen.add(clock.instant());
            scheduler.schedule(Duration.ofSeconds(10), tick[0]);
        };
        scheduler.schedule(Duration.ofSeconds(10), tick[0]);

        scheduler.advance(Duration.ofSeconds(35));
        assertEquals(List.of(T0.plusSeconds(10), T0.plusSeconds(20), T0.plusSeconds(30)), seen);
        assertEquals(1, scheduler.pending());
    }

    /**
     * Proves a cancelled task never runs, and that cancelling a second time returns false.
     */
    @Test
    void manualSchedulerCancelPreventsTheRun() {
        boolean[] ran = {false};
        Cancellable handle = scheduler.schedule(Duration.ofSeconds(1), () -> ran[0] = true);
        assertTrue(handle.cancel());
        scheduler.advance(Duration.ofSeconds(5));
        assertFalse(ran[0]);
        assertFalse(handle.cancel(), "nothing left to cancel");
    }

    /**
     * Proves the production scheduler runs a task on a real thread and can cancel a far-future one. This is the only test that uses real time.
     */
    @Test
    void executorSchedulerRunsForRealAndCanCancel() throws InterruptedException {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            Scheduler real = new ExecutorScheduler(executor);
            CountDownLatch ran = new CountDownLatch(1);
            real.schedule(Duration.ofMillis(20), ran::countDown);
            assertTrue(ran.await(5, TimeUnit.SECONDS));

            Cancellable never = real.schedule(Duration.ofHours(1), () -> { });
            assertTrue(never.cancel());
        } finally {
            executor.shutdownNow();
        }
    }
}
