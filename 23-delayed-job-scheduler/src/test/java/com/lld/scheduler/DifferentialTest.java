package com.lld.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Differential tests: the timing wheel is the clever structure, the heap is the obvious one. Feed both the same random program of schedule, cancel and advance-time steps (seeded) and demand identical firing logs.
 */
class DifferentialTest {

    /** One entry of a firing log: which job fired and at what virtual millisecond. */
    private record Fired(int label, long atMillis) {
    }

    /**
     * Runs a random program against a scheduler and returns the firing log.
     *
     * <p>Each step is one of: schedule a one-shot (delay 0 to 600 ms, so many are further away than one turn of a 64-bucket wheel), schedule a fixed-rate job, schedule a fixed-delay job, schedule a "parent" one-shot that schedules a child from inside its own run (child delay 0 to 20 ms), cancel a random earlier job, or move time forward and call runDue. Time moves 0 to {@code maxJump} ms; with a jump over 64 ms the wheel's one-turn limit in {@code advanceTo} is exercised. Time only moves in whole milliseconds, which is the wheel's tick, so the wheel is exact.
     */
    private static List<Fired> run(TimerScheduler s, FakeTicker t, long seed, int steps, int maxJump) {
        Random rnd = new Random(seed);
        List<Fired> log = new ArrayList<>();
        List<JobHandle> handles = new ArrayList<>();
        int[] labels = {0};
        for (int i = 0; i < steps; i++) {
            int op = rnd.nextInt(12);
            int label = labels[0]++;
            if (op < 5) {
                handles.add(s.schedule(() -> log.add(new Fired(label, t.elapsedMillis())), Duration.ofMillis(rnd.nextInt(601))));
            } else if (op == 5) {
                handles.add(s.scheduleAtFixedRate(() -> log.add(new Fired(label, t.elapsedMillis())), Duration.ofMillis(rnd.nextInt(50)), Duration.ofMillis(1 + rnd.nextInt(80))));
            } else if (op == 6) {
                handles.add(s.scheduleWithFixedDelay(() -> log.add(new Fired(label, t.elapsedMillis())), Duration.ofMillis(rnd.nextInt(50)), Duration.ofMillis(1 + rnd.nextInt(80))));
            } else if (op == 7) {
                long childDelay = rnd.nextInt(21);
                int childLabel = 100_000 + label;
                handles.add(s.schedule(() -> {
                    log.add(new Fired(label, t.elapsedMillis()));
                    s.schedule(() -> log.add(new Fired(childLabel, t.elapsedMillis())), Duration.ofMillis(childDelay));
                }, Duration.ofMillis(rnd.nextInt(300))));
            } else if (op < 10 && !handles.isEmpty()) {
                handles.get(rnd.nextInt(handles.size())).cancel();
            } else {
                t.advanceMillis(rnd.nextInt(maxJump + 1));
                s.runDue();
            }
        }
        for (int i = 0; i < 700; i++) {
            t.advanceMillis(1);
            s.runDue();
        }
        return log;
    }

    /** Proves the heap and the wheel fire the same jobs in the same order at the same virtual times for ten different seeds of 3,000 random steps, with time jumps of up to 7 ms. */
    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void wheelMatchesHeapOnRandomPrograms(long seed) {
        FakeTicker t1 = new FakeTicker();
        FakeTicker t2 = new FakeTicker();
        List<Fired> heap = run(TimerScheduler.withHeap(t1), t1, seed, 3000, 7);
        List<Fired> wheel = run(TimerScheduler.withWheel(t2, Duration.ofMillis(1), 64), t2, seed, 3000, 7);
        assertTrue(heap.size() > 200, "the program should fire plenty of jobs, got " + heap.size());
        assertEquals(heap, wheel);
    }

    /** Proves the wheel still matches the heap when a job far in the future shares a bucket with near ones (wheel of 4 buckets, due times up to 600 ticks away). */
    @Test
    void tinyWheelStillMatchesHeap() {
        FakeTicker t1 = new FakeTicker();
        FakeTicker t2 = new FakeTicker();
        List<Fired> heap = run(TimerScheduler.withHeap(t1), t1, 99, 2000, 7);
        List<Fired> wheel = run(TimerScheduler.withWheel(t2, Duration.ofMillis(1), 4), t2, 99, 2000, 7);
        assertEquals(heap, wheel);
    }

    /** Proves the wheel matches the heap when time jumps by up to 200 ms at once, which is more than one full turn of a 64-bucket wheel, for five seeds. */
    @ParameterizedTest
    @ValueSource(longs = {11, 12, 13, 14, 15})
    void wheelMatchesHeapOnJumpsLongerThanOneTurn(long seed) {
        FakeTicker t1 = new FakeTicker();
        FakeTicker t2 = new FakeTicker();
        List<Fired> heap = run(TimerScheduler.withHeap(t1), t1, seed, 3000, 200);
        List<Fired> wheel = run(TimerScheduler.withWheel(t2, Duration.ofMillis(1), 64), t2, seed, 3000, 200);
        assertTrue(heap.size() > 200, "the program should fire plenty of jobs, got " + heap.size());
        assertEquals(heap, wheel);
    }

    /** Proves the stated tie rule for a coarse wheel (10 ms tick): jobs inside one tick fire in scheduling order, not exact-time order, and never early. The heap orders them by exact time. */
    @Test
    void sameTickJobsFireInSchedulingOrderOnTheWheel() {
        FakeTicker t = new FakeTicker();
        TimerScheduler wheel = TimerScheduler.withWheel(t, Duration.ofMillis(10), 16);
        FakeTicker th = new FakeTicker();
        TimerScheduler heap = TimerScheduler.withHeap(th);
        List<String> w = new ArrayList<>();
        List<String> h = new ArrayList<>();
        wheel.schedule(() -> w.add("seven"), Duration.ofMillis(7));
        wheel.schedule(() -> w.add("three"), Duration.ofMillis(3));
        heap.schedule(() -> h.add("seven"), Duration.ofMillis(7));
        heap.schedule(() -> h.add("three"), Duration.ofMillis(3));

        t.advanceMillis(9);
        wheel.runDue();
        assertTrue(w.isEmpty(), "rounded up to the 10 ms tick, so not at 9 ms");
        t.advanceMillis(1);
        wheel.runDue();
        th.advanceMillis(10);
        heap.runDue();

        assertEquals(List.of("seven", "three"), w);
        assertEquals(List.of("three", "seven"), h);
    }

    /** Proves a huge jump in time (one hour on a 64-bucket wheel) is handled in one call and still fires everything due, in order. */
    @Test
    void bigTimeJumpFiresEverythingInOrder() {
        FakeTicker t = new FakeTicker();
        TimerScheduler s = TimerScheduler.withWheel(t, Duration.ofMillis(1), 64);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int id = i;
            s.schedule(() -> order.add(id), Duration.ofSeconds(200 - i));
        }
        t.advanceMillis(3_600_000);
        s.runDue();
        List<Integer> expected = new ArrayList<>();
        for (int i = 199; i >= 0; i--) {
            expected.add(i);
        }
        assertEquals(expected, order);
    }
}
