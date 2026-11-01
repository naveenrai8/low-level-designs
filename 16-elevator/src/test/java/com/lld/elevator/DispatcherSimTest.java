package com.lld.elevator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Proves the dispatchers, the percentile maths and the seeded simulation. */
class DispatcherSimTest {

    private final HallCall call = new HallCall(5, Direction.UP);

    /** Nearest picks the closest car and breaks ties by lower id. */
    @Test
    void nearestPicksClosest() {
        List<CarView> cars = List.of(new CarView(0, 0, Direction.IDLE, 0), new CarView(1, 7, Direction.IDLE, 0), new CarView(2, 4, Direction.IDLE, 0));
        assertEquals(2, new NearestDispatcher().pick(call, cars));
        List<CarView> tie = List.of(new CarView(0, 3, Direction.IDLE, 0), new CarView(1, 7, Direction.IDLE, 0));
        assertEquals(0, new NearestDispatcher().pick(call, tie));
    }

    /** Least-loaded prefers the shortest queue even if that car is farther. */
    @Test
    void leastLoadedPicksShortestQueue() {
        List<CarView> cars = List.of(new CarView(0, 5, Direction.IDLE, 4), new CarView(1, 0, Direction.IDLE, 1));
        assertEquals(1, new LeastLoadedDispatcher().pick(call, cars));
    }

    /** Zoned sends the band owner, and falls back to nearest when the owner is not eligible. */
    @Test
    void zonedUsesOwnerThenFallsBack() {
        ZonedDispatcher z = new ZonedDispatcher(20, 4);
        List<CarView> all = List.of(new CarView(0, 0, Direction.IDLE, 0), new CarView(1, 5, Direction.IDLE, 0), new CarView(2, 10, Direction.IDLE, 0), new CarView(3, 15, Direction.IDLE, 0));
        assertEquals(0, z.pick(new HallCall(4, Direction.UP), all));
        assertEquals(3, z.pick(new HallCall(19, Direction.DOWN), all));
        List<CarView> noOwner = List.of(all.get(0), all.get(1), all.get(2));
        assertEquals(2, z.pick(new HallCall(17, Direction.DOWN), noOwner));
    }

    /** A zoned dispatcher refuses cars it was not configured for, instead of silently mis-zoning. */
    @Test
    void zonedRejectsUnknownCars() {
        ZonedDispatcher z = new ZonedDispatcher(20, 2);
        List<CarView> three = List.of(new CarView(0, 0, Direction.IDLE, 0), new CarView(2, 0, Direction.IDLE, 0));
        assertThrows(IllegalArgumentException.class, () -> z.pick(call, three));
    }

    /** Hall call end to end: the nearest car is sent, and it opens at the floor. */
    @Test
    void hallCallEndToEnd() {
        VirtualTicker t = new VirtualTicker();
        Building b = new Building(10, new int[] {0, 8}, new NearestDispatcher(), t);
        assertEquals(1, b.hallCall(6, Direction.DOWN).carId());
        List<Served> stops = List.of();
        for (int i = 0; i < 5 && stops.isEmpty(); i++) {
            stops = b.step();
            t.advance();
        }
        assertEquals(List.of(new Served(1, 6, Served.Kind.HALL_DOWN, 2)), stops);
    }

    /** Nearest-rank p95 on a known shuffled list of 20 values is the 19th smallest. */
    @Test
    void percentileNearestRankKnownList() {
        List<Long> v = new java.util.ArrayList<>();
        for (long i = 20; i >= 1; i--) {
            v.add(i);
        }
        java.util.Collections.shuffle(v, new java.util.Random(1));
        assertEquals(19, Stats.percentileNearestRank(v, 95));
        assertEquals(20, Stats.percentileNearestRank(v, 100));
        assertEquals(10, Stats.percentileNearestRank(v, 50));
        assertEquals(1, Stats.percentileNearestRank(v, 1));
        assertEquals(7, Stats.percentileNearestRank(List.of(7L), 95));
        assertEquals(10.5, Stats.mean(v));
        assertThrows(IllegalArgumentException.class, () -> Stats.percentileNearestRank(List.of(), 95));
    }

    /** The same seed gives identical riders and identical results; a different seed gives different riders. */
    @Test
    void sameSeedSameResults() {
        List<Rider> a = Workload.generate(42, 20, 300, 30);
        List<Rider> b = Workload.generate(42, 20, 300, 30);
        assertEquals(a, b);
        assertNotEquals(a, Workload.generate(43, 20, 300, 30));
        SimResult r1 = Simulation.run("nearest", a, 20, 4, new NearestDispatcher());
        SimResult r2 = Simulation.run("nearest", b, 20, 4, new NearestDispatcher());
        assertEquals(r1, r2);
    }

    /** Every rider is delivered and the waits are sane for all three dispatchers. */
    @Test
    void allRidersDelivered() {
        List<Rider> riders = Workload.generate(5, 12, 200, 30);
        for (Dispatcher d : List.of(new NearestDispatcher(), new LeastLoadedDispatcher(), new ZonedDispatcher(12, 3))) {
            SimResult r = Simulation.run("x", riders, 12, 3, d);
            assertEquals(riders.size(), r.riders());
            assertTrue(r.p95Wait() <= r.maxWait());
            assertTrue(r.meanWait() >= 0);
        }
    }
}
