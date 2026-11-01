package com.lld.dispatch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** F1: scoring strategies, greedy versus optimal assignment, the batching window, and the seeded simulation. */
class BatchTest {

    /** Sum of the chosen costs; unassigned rows add nothing. */
    private static long total(long[][] c, int[] pick) {
        long t = 0;
        for (int i = 0; i < pick.length; i++) {
            if (pick[i] >= 0) {
                t += c[i][pick[i]];
            }
        }
        return t;
    }

    /** Best total over every permutation, for a square table (the reference answer). */
    private static long brute(long[][] c, int row, boolean[] used) {
        if (row == c.length) {
            return 0;
        }
        long best = Long.MAX_VALUE;
        for (int j = 0; j < c[0].length; j++) {
            if (!used[j]) {
                used[j] = true;
                best = Math.min(best, c[row][j] + brute(c, row + 1, used));
                used[j] = false;
            }
        }
        return best;
    }

    /** The README table: greedy takes the cheapest pair (4) and pays 16 after it; the optimum is 6 + 6. */
    @Test
    void greedyGapOnTheTinyExample() {
        long[][] c = {{4, 6}, {6, 16}};
        assertArrayEquals(new int[] {0, 1}, new GreedyAssigner().assign(c));
        assertEquals(20, total(c, new GreedyAssigner().assign(c)));
        assertArrayEquals(new int[] {1, 0}, new OptimalAssigner().assign(c));
        assertEquals(12, total(c, new OptimalAssigner().assign(c)));
    }

    /** Hungarian equals brute-force permutations on 2000 seeded random square tables of size 1..7, and greedy is never better. */
    @Test
    void optimalMatchesBruteForce() {
        Random rnd = new Random(99);
        int greedyWorse = 0;
        for (int t = 0; t < 2000; t++) {
            int n = 1 + rnd.nextInt(7);
            long[][] c = new long[n][n];
            for (long[] row : c) {
                for (int j = 0; j < n; j++) {
                    row[j] = rnd.nextInt(50);
                }
            }
            long opt = total(c, new OptimalAssigner().assign(c));
            assertEquals(brute(c, 0, new boolean[n]), opt, "table " + t);
            long gr = total(c, new GreedyAssigner().assign(c));
            assertTrue(gr >= opt);
            if (gr > opt) {
                greedyWorse++;
            }
        }
        assertTrue(greedyWorse > 100, "greedy was worse in " + greedyWorse);
    }

    /** More requests than drivers, and forbidden pairs: the optimal assigner serves as many as it can and never uses a forbidden pair. */
    @Test
    void optimalHandlesShortSupplyAndForbiddenPairs() {
        long f = BatchAssigner.FORBIDDEN;
        long[][] c = {{5, f}, {1, f}, {3, f}};
        int[] pick = new OptimalAssigner().assign(c);
        assertEquals(1, java.util.Arrays.stream(pick).filter(x -> x >= 0).count());
        assertEquals(0, pick[1]);
        long[][] d = {{f, 2}, {f, 9}};
        assertArrayEquals(new int[] {1, -1}, new OptimalAssigner().assign(d));
        assertArrayEquals(new int[] {1, -1}, new GreedyAssigner().assign(d));
    }

    /** Builds a fleet with the given (id, x) drivers on the x axis. */
    private static Fleet lineFleet(ManualScheduler s, long... idAndX) {
        Fleet f = new Fleet(10, s::nowMs);
        for (int i = 0; i < idAndX.length; i += 2) {
            f.register(idAndX[i], new Point(0, 0), 50, 10);
            f.goOnline(idAndX[i], new Point(idAndX[i + 1], 0));
        }
        return f;
    }

    /** The README example: R1 at 0 arrives first, R2 at 6 later, D1 at 5, D2 at -20. Immediate greedy pays 5+26=31; a batch pays 1+20=21. */
    @Test
    void immediateGreedyCanBeWorseThanABatch() {
        ManualScheduler s1 = new ManualScheduler();
        Fleet f1 = lineFleet(s1, 1, 5, 2, -20);
        Dispatcher imm = new Dispatcher(f1, s1, new RatioSurgePolicy(0, 10_000), ScoringStrategy.DISTANCE, new GreedyAssigner(), 0, 1000, 100);
        imm.submit(new Request(1, new Point(0, 0)));
        imm.submit(new Request(2, new Point(6, 0)));
        assertEquals(1, imm.driverOf(1));
        assertEquals(2, imm.driverOf(2));
        ManualScheduler s2 = new ManualScheduler();
        Fleet f2 = lineFleet(s2, 1, 5, 2, -20);
        Dispatcher bat = new Dispatcher(f2, s2, new RatioSurgePolicy(0, 10_000), ScoringStrategy.DISTANCE, new GreedyAssigner(), 500, 1000, 100);
        bat.submit(new Request(1, new Point(0, 0)));
        s2.advance(100);
        bat.submit(new Request(2, new Point(6, 0)));
        assertEquals(Dispatcher.Status.PENDING, bat.status(1));
        s2.advance(400);
        assertEquals(2, bat.driverOf(1));
        assertEquals(1, bat.driverOf(2));
    }

    /** Nothing is offered before the window ends, and a request that arrives after a flush waits for the next window. */
    @Test
    void windowHoldsRequestsUntilItEnds() {
        ManualScheduler s = new ManualScheduler();
        Fleet f = lineFleet(s, 1, 5);
        Dispatcher d = new Dispatcher(f, s, new RatioSurgePolicy(0, 10_000), ScoringStrategy.DISTANCE, new GreedyAssigner(), 1000, 5000, 100);
        d.submit(new Request(1, new Point(0, 0)));
        s.advance(999);
        assertEquals(Dispatcher.Status.PENDING, d.status(1));
        s.advance(1);
        assertEquals(Dispatcher.Status.OFFERED, d.status(1));
    }

    /** Distance, rating and ETA strategies rank the same two drivers differently. */
    @Test
    void scoringStrategiesChangeTheChoice() {
        Point pickup = new Point(0, 0);
        DriverInfo near = new DriverInfo(1, new Point(100, 0), 30, 5);
        DriverInfo far = new DriverInfo(2, new Point(120, 0), 50, 20);
        assertTrue(ScoringStrategy.DISTANCE.cost(pickup, near) < ScoringStrategy.DISTANCE.cost(pickup, far));
        ScoringStrategy rated = ScoringStrategy.distanceAndRating(5);
        assertEquals(100 + 20 * 5, rated.cost(pickup, near));
        assertTrue(rated.cost(pickup, far) < rated.cost(pickup, near));
        assertEquals(200, ScoringStrategy.ETA.cost(pickup, near));
        assertEquals(60, ScoringStrategy.ETA.cost(pickup, far));
    }

    /** A strategy that returns a negative or huge cost is refused before anything changes. */
    @Test
    void badCostIsRejected() {
        GridIndex g = new GridIndex(10);
        g.upsert(1, new Point(1, 1));
        List<Request> batch = List.of(new Request(1, new Point(0, 0)));
        DriverInfo info = new DriverInfo(1, new Point(1, 1), 50, 10);
        DispatchException e = assertThrows(DispatchException.class, () -> BatchMatcher.match(batch, g, id -> info, (r, d) -> true, (p, d) -> -1, new GreedyAssigner()));
        assertEquals(DispatchException.Code.BAD_COST, e.code());
        e = assertThrows(DispatchException.class, () -> BatchMatcher.match(batch, g, id -> info, (r, d) -> true, (p, d) -> ScoringStrategy.MAX_COST + 1, new GreedyAssigner()));
        assertEquals(DispatchException.Code.BAD_COST, e.code());
    }

    /** The batch matcher with distance scoring equals a brute-force best total over seeded random worlds (a differential check of candidate pruning). */
    @Test
    void matcherIsOptimalForDistanceScoring() {
        Random rnd = new Random(5);
        for (int w = 0; w < 300; w++) {
            int nr = 1 + rnd.nextInt(5);
            int nd = nr + rnd.nextInt(5);
            GridIndex g = new GridIndex(20);
            Map<Long, DriverInfo> infos = new LinkedHashMap<>();
            for (long id = 1; id <= nd; id++) {
                Point p = new Point(rnd.nextInt(200) - 100, rnd.nextInt(200) - 100);
                g.upsert(id, p);
                infos.put(id, new DriverInfo(id, p, 50, 10));
            }
            List<Request> batch = new ArrayList<>();
            for (long r = 1; r <= nr; r++) {
                batch.add(new Request(r, new Point(rnd.nextInt(200) - 100, rnd.nextInt(200) - 100)));
            }
            Map<Long, Long> got = BatchMatcher.match(batch, g, infos::get, (r, d) -> true, ScoringStrategy.DISTANCE, new OptimalAssigner());
            assertEquals(nr, got.size());
            long gotTotal = 0;
            for (Request r : batch) {
                gotTotal += ScoringStrategy.DISTANCE.cost(r.pickup(), infos.get(got.get(r.id())));
            }
            long[][] full = new long[nr][nd];
            for (int i = 0; i < nr; i++) {
                for (int j = 0; j < nd; j++) {
                    full[i][j] = ScoringStrategy.DISTANCE.cost(batch.get(i).pickup(), infos.get((long) j + 1));
                }
            }
            long[][] sq = new long[nd][nd];
            for (int i = 0; i < nd; i++) {
                for (int j = 0; j < nd; j++) {
                    sq[i][j] = i < nr ? full[i][j] : 0;
                }
            }
            assertEquals(brute(sq, 0, new boolean[nd]), gotTotal, "world " + w);
        }
    }

    /** The simulation is deterministic: the same seed gives the same numbers twice. */
    @Test
    void simulationIsDeterministic() {
        assertEquals(Simulation.run(1, 40, 200, 120, 60, 10_000, 3000, new OptimalAssigner()), Simulation.run(1, 40, 200, 120, 60, 10_000, 3000, new OptimalAssigner()));
    }

    /** A driver who declined request 1 must not come back through another request's candidates: R1(0,0) declined D1(5,0), R2(100,0), D2(200,0). The right answer is R1 to D2 and R2 to D1. */
    @Test
    void declinerIsNeverUsedEvenViaAnotherRequestsCandidates() {
        GridIndex g = new GridIndex(10);
        g.upsert(1, new Point(5, 0));
        g.upsert(2, new Point(200, 0));
        Map<Long, DriverInfo> infos = new LinkedHashMap<>();
        infos.put(1L, new DriverInfo(1, new Point(5, 0), 50, 10));
        infos.put(2L, new DriverInfo(2, new Point(200, 0), 50, 10));
        List<Request> batch = List.of(new Request(1, new Point(0, 0)), new Request(2, new Point(100, 0)));
        Map<Long, Long> got = BatchMatcher.match(batch, g, infos::get, (r, d) -> !(r.id() == 1 && d == 1), ScoringStrategy.DISTANCE, new OptimalAssigner());
        assertEquals(2L, got.get(1L));
        assertEquals(1L, got.get(2L));
        got = BatchMatcher.match(batch, g, infos::get, (r, d) -> !(r.id() == 1 && d == 1), ScoringStrategy.DISTANCE, new GreedyAssigner());
        assertEquals(2L, got.get(1L));
    }

    /** Brute force for rectangular tables with forbidden entries: the most requests served first, then the lowest total cost. Returns {served, cost}. */
    private static long[] bruteRect(long[][] c, int row, boolean[] used) {
        if (row == c.length) {
            return new long[] {0, 0};
        }
        long[] best = bruteRect(c, row + 1, used);
        for (int j = 0; j < c[0].length; j++) {
            if (!used[j] && c[row][j] < BatchAssigner.FORBIDDEN) {
                used[j] = true;
                long[] sub = bruteRect(c, row + 1, used);
                used[j] = false;
                long served = sub[0] + 1;
                long cost = sub[1] + c[row][j];
                if (served > best[0] || (served == best[0] && cost < best[1])) {
                    best = new long[] {served, cost};
                }
            }
        }
        return best;
    }

    /** The Hungarian assigner equals brute force on 3000 seeded rectangular tables (1 to 6 rows, 1 to 6 columns) with about a third of the pairs forbidden, and never uses a forbidden pair. */
    @Test
    void optimalMatchesBruteForceOnRectangularTablesWithForbiddenPairs() {
        Random rnd = new Random(31);
        for (int t = 0; t < 3000; t++) {
            int n = 1 + rnd.nextInt(6);
            int m = 1 + rnd.nextInt(6);
            long[][] c = new long[n][m];
            for (long[] row : c) {
                for (int j = 0; j < m; j++) {
                    row[j] = rnd.nextInt(3) == 0 ? BatchAssigner.FORBIDDEN : rnd.nextInt(40);
                }
            }
            int[] pick = new OptimalAssigner().assign(c);
            long served = 0;
            long cost = 0;
            boolean[] used = new boolean[m];
            for (int i = 0; i < n; i++) {
                if (pick[i] >= 0) {
                    assertTrue(c[i][pick[i]] < BatchAssigner.FORBIDDEN, "forbidden pair used in table " + t);
                    assertFalse(used[pick[i]]);
                    used[pick[i]] = true;
                    served++;
                    cost += c[i][pick[i]];
                }
            }
            long[] want = bruteRect(c, 0, new boolean[m]);
            assertEquals(want[0], served, "served in table " + t);
            assertEquals(want[1], cost, "cost in table " + t);
        }
    }

    /** More than 256 requests in one batch are refused; ten requests at one spot with 50 drivers gather exactly 8 candidates (the cap), not 10 or 50. */
    @Test
    void batchAndCandidateLimits() {
        GridIndex g = new GridIndex(10);
        Map<Long, DriverInfo> infos = new LinkedHashMap<>();
        for (long id = 1; id <= 50; id++) {
            Point p = new Point(id, 0);
            g.upsert(id, p);
            infos.put(id, new DriverInfo(id, p, 50, 10));
        }
        List<Request> big = new ArrayList<>();
        for (long r = 1; r <= BatchMatcher.MAX_BATCH + 1; r++) {
            big.add(new Request(r, new Point(0, 0)));
        }
        assertEquals(DispatchException.Code.OUT_OF_RANGE, assertThrows(DispatchException.class, () -> BatchMatcher.gather(big, g, infos::get, (r, d) -> true)).code());
        List<Request> ten = new ArrayList<>();
        for (long r = 1; r <= 10; r++) {
            ten.add(new Request(r, new Point(0, 0)));
        }
        BatchMatcher.Candidates c = BatchMatcher.gather(ten, g, infos::get, (r, d) -> true);
        assertEquals(BatchMatcher.CANDIDATES_PER_REQUEST, c.ids().size());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), c.ids());
    }

    /** A strategy that fails once: with window 0 the failed submit leaves no trace (status is unknown, nothing active), and the same id can be retried successfully. */
    @Test
    void failedSubmitLeavesNothingBehind() {
        ManualScheduler s = new ManualScheduler();
        Fleet f = lineFleet(s, 1, 5);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ScoringStrategy flaky = (p, d) -> calls.getAndIncrement() == 0 ? -1 : 7;
        Dispatcher d = new Dispatcher(f, s, new RatioSurgePolicy(0, 10_000), flaky, new GreedyAssigner(), 0, 1000, 100);
        assertEquals(DispatchException.Code.BAD_COST, assertThrows(DispatchException.class, () -> d.submit(new Request(1, new Point(0, 0)))).code());
        assertEquals(0, d.activeCount());
        assertEquals(DispatchException.Code.UNKNOWN_REQUEST, assertThrows(DispatchException.class, () -> d.status(1)).code());
        assertEquals(DriverState.AVAILABLE, f.state(1));
        assertEquals(0, s.pendingCount());
        d.submit(new Request(1, new Point(0, 0)));
        assertEquals(1, d.driverOf(1));
    }

    /** A flush from the window timer that throws must not leave the dispatcher stuck: the next submit schedules a fresh flush and the batch is served. */
    @Test
    void failedWindowFlushDoesNotStickTheFlag() {
        ManualScheduler s = new ManualScheduler();
        Fleet f = lineFleet(s, 1, 5, 2, 50);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ScoringStrategy flaky = (p, d) -> calls.getAndIncrement() == 0 ? -1 : 7;
        Dispatcher d = new Dispatcher(f, s, new RatioSurgePolicy(0, 10_000), flaky, new GreedyAssigner(), 500, 5000, 100);
        d.submit(new Request(1, new Point(0, 0)));
        assertEquals(DispatchException.Code.BAD_COST, assertThrows(DispatchException.class, () -> s.advance(500)).code());
        assertEquals(Dispatcher.Status.PENDING, d.status(1));
        d.submit(new Request(2, new Point(1, 0)));
        s.advance(500);
        assertEquals(Dispatcher.Status.OFFERED, d.status(1));
        assertEquals(Dispatcher.Status.OFFERED, d.status(2));
    }

    /** Rating penalty uses exact arithmetic: an absurd penalty overflows loudly instead of wrapping to a small or negative cost. */
    @Test
    void ratingPenaltyOverflowIsLoud() {
        DriverInfo low = new DriverInfo(1, new Point(1, 1), 0, 10);
        assertThrows(ArithmeticException.class, () -> ScoringStrategy.distanceAndRating(Long.MAX_VALUE).cost(new Point(0, 0), low));
        assertEquals(DispatchException.Code.OUT_OF_RANGE, assertThrows(DispatchException.class, () -> ScoringStrategy.distanceAndRating(-1)).code());
    }

    /** Virtual time cannot run backwards. */
    @Test
    void schedulerRejectsNegativeAdvance() {
        assertThrows(IllegalArgumentException.class, () -> new ManualScheduler().advance(-1));
    }

    /** Runs one policy over seeds 1..10 and returns {mean distance, p95 distance, mean wait ms, p95 wait ms}, each averaged over the seeds (rounded down). */
    private static long[] average(int drivers, int requests, int horizon, int trip, long windowMs, BatchAssigner a) {
        long[] sum = new long[4];
        for (int seed = 1; seed <= 10; seed++) {
            Simulation.Result r = Simulation.run(seed, drivers, requests, horizon, trip, 10_000, windowMs, a);
            assertEquals(requests, r.assigned());
            sum[0] += r.meanMetres();
            sum[1] += r.p95Metres();
            sum[2] += r.meanWaitMs();
            sum[3] += r.p95WaitMs();
        }
        for (int i = 0; i < 4; i++) {
            sum[i] /= 10;
        }
        return sum;
    }

    /** Formats one table cell as "mean/p95 metres, mean/p95 wait in seconds". */
    private static String cell(long[] v) {
        return v[0] + "/" + v[1] + " m, " + v[2] / 1000 + "/" + v[3] / 1000 + " s";
    }

    /** Measures three loads (light, balanced, overloaded) for three policies, prints the rows the README quotes, and pins the exact numbers so the docs cannot drift from the code. */
    @Test
    void simulationTable() {
        int[][] setups = {{100, 300, 300, 30}, {60, 300, 300, 60}, {40, 300, 300, 60}};
        String[] expected = EXPECTED_SIM;
        String[] lines = new String[setups.length];
        for (int i = 0; i < setups.length; i++) {
            int[] c = setups[i];
            long[] imm = average(c[0], c[1], c[2], c[3], 0, new GreedyAssigner());
            long[] bg = average(c[0], c[1], c[2], c[3], 5000, new GreedyAssigner());
            long[] bo = average(c[0], c[1], c[2], c[3], 5000, new OptimalAssigner());
            String line = "drivers=" + c[0] + " trip=" + c[3] + " | " + cell(imm) + " | " + cell(bg) + " | " + cell(bo);
            System.out.println("SIM " + line);
            lines[i] = line;
        }
        assertEquals(String.join("\n", expected), String.join("\n", lines));
    }

    /** The measured rows (seeds 1 to 10, 300 requests over 300 s, 10 km square, window 5 s); capacity is drivers * 300 / trip trips: 1000, 300 and 200. */
    private static final String[] EXPECTED_SIM = {
        "drivers=100 trip=30 | 715/1629 m, 0/0 s | 711/1662 m, 3/5 s | 710/1638 m, 3/5 s",
        "drivers=60 trip=60 | 2684/6982 m, 3/15 s | 2097/6081 m, 7/26 s | 2004/5054 m, 7/25 s",
        "drivers=40 trip=60 | 1311/4050 m, 61/213 s | 1221/3749 m, 66/219 s | 1212/3718 m, 66/219 s"};
}
