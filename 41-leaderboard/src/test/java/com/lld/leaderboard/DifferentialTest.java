package com.lld.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Runs long random sequences on the fast board and the naive model side by side and demands identical answers. */
class DifferentialTest {

    /** Proves that for several seeds, thousands of random add/increment/remove/rank/top/range operations agree with the brute-force model at every step. */
    @Test
    void randomOperationsMatchNaiveModel() {
        for (long seed = 1; seed <= 20; seed++) {
            run(seed, 1500);
        }
    }

    /** Runs {@code steps} random operations for one seed, checking results after each. */
    private void run(long seed, int steps) {
        Random rnd = new Random(seed);
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        SortedLeaderboard fast = new SortedLeaderboard(clock);
        NaiveBoard slow = new NaiveBoard();
        for (int i = 0; i < steps; i++) {
            // Small score range on purpose, so ties are very common. Time often does not move, so same-millisecond ties happen too.
            if (rnd.nextInt(3) == 0) {
                clock.advance(Duration.ofMillis(rnd.nextInt(3)));
            }
            String user = "u" + rnd.nextInt(40);
            long now = clock.millis();
            switch (rnd.nextInt(7)) {
                case 0, 1 -> {
                    long s = rnd.nextInt(15);
                    fast.setScore(user, s);
                    slow.set(user, s, now);
                }
                case 2, 3 -> {
                    long d = rnd.nextInt(11) - 4;
                    long next = (slow.score(user) == null ? 0 : slow.score(user)) + d;
                    assertEquals(next, fast.increment(user, d));
                    slow.set(user, next, now);
                }
                case 4 -> assertEquals(slow.remove(user), fast.remove(user));
                case 5 -> {
                    assertEquals(slow.rank(user), fast.rank(user).orElse(0));
                    assertEquals(slow.competitionRank(user), fast.competitionRank(user).orElse(0));
                }
                default -> {
                    int from = 1 + rnd.nextInt(20);
                    int to = from + rnd.nextInt(10);
                    List<Ranked> all = slow.sorted();
                    assertEquals(all.subList(0, Math.min(from + 3, all.size())), fast.top(from + 3));
                    assertEquals(all.subList(Math.min(from - 1, all.size()), Math.min(to, all.size())), fast.range(from, to));
                }
            }
            assertEquals(slow.size(), fast.size());
        }
        assertEquals(slow.sorted(), fast.top(Integer.MAX_VALUE));
    }
}
