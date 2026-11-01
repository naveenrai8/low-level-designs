package com.lld.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Checks both windows against a slow, obviously correct list-based model over thousands of random steps. */
class WindowDifferentialTest {

    /** The count window must agree with "look at the last N entries of a plain list". */
    @Test
    void countWindowMatchesNaiveModel() {
        Random rnd = new Random(5);
        for (int size : new int[] {1, 3, 7}) {
            CountWindow w = new CountWindow(size);
            List<Boolean> all = new ArrayList<>();
            for (int i = 0; i < 2_000; i++) {
                boolean ok = rnd.nextInt(3) != 0;
                w.record(ok, 0);
                all.add(ok);
                List<Boolean> tail = all.subList(Math.max(0, all.size() - size), all.size());
                assertEquals(tail.size(), w.total(0));
                assertEquals(tail.stream().filter(b -> !b).count(), w.failures(0));
            }
        }
    }

    /** The time window must agree with "keep entries whose age is less than the window". */
    @Test
    void timeWindowMatchesNaiveModel() {
        Random rnd = new Random(9);
        long window = 50;
        TimeWindow w = new TimeWindow(window);
        List<long[]> all = new ArrayList<>();
        long now = 0;
        for (int i = 0; i < 3_000; i++) {
            now += rnd.nextInt(8);
            boolean ok = rnd.nextInt(3) != 0;
            w.record(ok, now);
            all.add(new long[] {now, ok ? 1 : 0});
            long at = now;
            List<long[]> live = all.stream().filter(e -> at - e[0] < window).toList();
            assertEquals(live.size(), w.total(now));
            assertEquals(live.stream().filter(e -> e[1] == 0).count(), w.failures(now));
        }
    }
}
