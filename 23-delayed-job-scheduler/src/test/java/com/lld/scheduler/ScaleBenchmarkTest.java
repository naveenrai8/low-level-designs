package com.lld.scheduler;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * An opt-in benchmark, off by default. Run it with {@code -Dscheduler.bench=true}. It is the only place in this module that reads the real clock ({@code System.nanoTime}); the scheduler itself still runs on a fake ticker.
 */
@EnabledIfSystemProperty(named = "scheduler.bench", matches = "true")
class ScaleBenchmarkTest {

    /** Runs the schedule + cancel (20%) + fire workload for several sizes and prints heap and wheel milliseconds, best of the timed runs after warm-up. */
    @Test
    void heapVersusWheel() {
        int[] sizes = {1_000, 10_000, 100_000, 1_000_000};
        System.out.println("max heap (Runtime.maxMemory) = " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MiB");
        System.out.println("n,heap_ms,wheel_ms (best of 5 after 3 warm-up runs; schedule N jobs over 60 s, cancel 20%, fire all)");
        for (int n : sizes) {
            double heap = best(n, false);
            double wheel = best(n, true);
            System.out.printf("%d,%.1f,%.1f%n", n, heap, wheel);
        }
    }

    /** Returns the best (smallest) wall time in milliseconds of 5 measured runs after 3 warm-up runs. */
    private static double best(int n, boolean wheel) {
        for (int i = 0; i < 3; i++) {
            once(n, wheel);
        }
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            best = Math.min(best, once(n, wheel));
        }
        return best;
    }

    /** One run: schedule n jobs at random times in the next 60 s (1 ms tick), cancel every fifth, then move time in 1 ms steps for 60 s firing everything. Returns elapsed milliseconds. */
    private static double once(int n, boolean wheel) {
        FakeTicker t = new FakeTicker();
        TimerScheduler s = wheel ? TimerScheduler.withWheel(t, Duration.ofMillis(1), 4096) : TimerScheduler.withHeap(t);
        Random rnd = new Random(5);
        JobHandle[] hs = new JobHandle[n];
        Job noop = () -> { };
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            hs[i] = s.schedule(noop, Duration.ofMillis(rnd.nextInt(60_000)));
        }
        for (int i = 0; i < n; i += 5) {
            hs[i].cancel();
        }
        for (int ms = 0; ms <= 60_000; ms++) {
            s.runDue();
            t.advanceMillis(1);
        }
        if (s.pending() != 0) {
            throw new IllegalStateException("jobs left over");
        }
        return (System.nanoTime() - start) / 1e6;
    }
}
