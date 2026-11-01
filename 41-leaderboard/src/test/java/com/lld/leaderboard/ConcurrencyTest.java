package com.lld.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Many threads hammering one shared board behind a start gate; totals and structure must be exact. */
class ConcurrencyTest {

    private static final int THREADS = 8;
    private static final int PER_THREAD = 2_000;

    /** Runs {@code body} on THREADS threads released together by a latch, and rethrows any failure. */
    private void hammer(java.util.function.IntConsumer body) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch ready = new CountDownLatch(THREADS);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                int id = t;
                done.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    body.accept(id);
                    return null;
                }));
            }
            ready.await();
            go.countDown();
            for (Future<?> f : done) {
                f.get(8, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Reads while writers are running and asserts what must hold at any instant: top rows are numbered 1..n, users are distinct, scores never go up, and a rank is within 1..50. */
    private static void checkReaders(Leaderboard b, String user) {
        List<Ranked> rows = b.top(5);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(i + 1, rows.get(i).rank());
            assertTrue(seen.add(rows.get(i).user()), "duplicate user in top");
            if (i > 0) {
                assertTrue(rows.get(i - 1).score() >= rows.get(i).score());
            }
        }
        b.rank(user).ifPresent(r -> assertTrue(r >= 1 && r <= 50, "rank " + r));
    }

    /** Proves increments on the same user from all threads lose nothing: the exact total is THREADS * PER_THREAD. */
    @Test
    void sameUserLosesNoUpdates() throws Exception {
        ConcurrentLeaderboard b = new ConcurrentLeaderboard(new SortedLeaderboard(new MutableClock(Instant.EPOCH)));
        hammer(id -> {
            for (int i = 0; i < PER_THREAD; i++) {
                b.increment("hot", 1);
            }
        });
        assertEquals((long) THREADS * PER_THREAD, b.score("hot").getAsLong());
        assertEquals(1, b.size());
    }

    /** Proves increments on different users, mixed with reads, leave exact per-user totals, a unique rank for each user and the right size. */
    @Test
    void differentUsersStayConsistent() throws Exception {
        ConcurrentLeaderboard b = new ConcurrentLeaderboard(new SortedLeaderboard(new MutableClock(Instant.EPOCH)));
        hammer(id -> {
            for (int i = 0; i < PER_THREAD; i++) {
                b.increment("u" + (i % 50), 1);
                if (i % 10 == 0) {
                    checkReaders(b, "u" + id);
                }
            }
        });
        assertEquals(50, b.size());
        long total = 0;
        boolean[] seen = new boolean[51];
        for (int u = 0; u < 50; u++) {
            total += b.score("u" + u).getAsLong();
            OptionalInt r = b.rank("u" + u);
            assertTrue(r.isPresent() && !seen[r.getAsInt()], "rank must be unique");
            seen[r.getAsInt()] = true;
        }
        assertEquals((long) THREADS * PER_THREAD, total);
        assertEquals(50, b.top(100).size());
    }

    /** Proves the windowed board loses no update under the same hammering: exact total for one hot user in one window. */
    @Test
    void windowedBoardLosesNoUpdates() throws Exception {
        WindowedLeaderboard w = new WindowedLeaderboard(Window.DAILY, java.time.ZoneOffset.UTC, new MutableClock(Instant.parse("2026-03-01T10:00:00Z")), 2);
        hammer(id -> {
            for (int i = 0; i < PER_THREAD; i++) {
                w.increment("hot", 1);
                w.increment("u" + (i % 20), 1);
            }
        });
        List<Ranked> rows = w.top(Instant.parse("2026-03-01T10:00:00Z"), 100);
        assertEquals(21, rows.size());
        long total = 0;
        for (Ranked r : rows) {
            total += r.score();
        }
        assertEquals(2L * THREADS * PER_THREAD, total);
        assertEquals(1, w.windowStarts().size());
    }
}
