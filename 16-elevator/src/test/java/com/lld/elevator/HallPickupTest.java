package com.lld.elevator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Proves a rider picked up on a hall call is carried the way they asked, and that one button belongs to one car. */
class HallPickupTest {

    private final VirtualTicker ticker = new VirtualTicker();

    /** Steps the building (advancing virtual time) until it makes a stop, and returns that tick's stops. Fails after 100 ticks. */
    private List<Served> stepToNextStop(Building b) {
        for (int i = 0; i < 100; i++) {
            List<Served> s = b.step();
            ticker.advance();
            if (!s.isEmpty()) {
                return s;
            }
        }
        throw new AssertionError("no stop within 100 ticks");
    }

    /** UP rider picked up at 5 presses 9 right after the doors open: the car must go on to 6, not turn down for the DOWN call at 2. */
    @Test
    void upPickupIsCarriedUp() {
        Building b = new Building(10, new int[] {0}, new NearestDispatcher(), ticker);
        b.hallCall(5, Direction.UP);
        b.hallCall(2, Direction.DOWN);
        List<Served> stop = stepToNextStop(b);
        assertEquals(List.of(new Served(0, 5, Served.Kind.HALL_UP, stop.get(0).tick())), stop);
        b.cabRequest(0, 9);
        b.step();
        assertEquals(6, b.cars().get(0).floor());
        assertEquals(Direction.UP, b.cars().get(0).direction());
    }

    /** Mirror image: DOWN rider picked up at 4 presses 0, and the car must go on to 3, not turn up for the UP call at 7. */
    @Test
    void downPickupIsCarriedDown() {
        Building b = new Building(10, new int[] {9}, new NearestDispatcher(), ticker);
        b.hallCall(4, Direction.DOWN);
        b.hallCall(7, Direction.UP);
        List<Served> stop = stepToNextStop(b);
        assertEquals(Served.Kind.HALL_DOWN, stop.get(0).kind());
        assertEquals(4, stop.get(0).floor());
        b.cabRequest(0, 0);
        b.step();
        assertEquals(3, b.cars().get(0).floor());
    }

    /** An UP and a DOWN call at the same floor are served one at a time: UP first, then the car sweeps up and returns for DOWN. */
    @Test
    void bothDirectionsAtOneFloorAreServedSeparately() {
        Building b = new Building(10, new int[] {0}, new NearestDispatcher(), ticker);
        b.hallCall(5, Direction.UP);
        b.hallCall(5, Direction.DOWN);
        List<Served> first = stepToNextStop(b);
        assertEquals(1, first.size());
        assertEquals(Served.Kind.HALL_UP, first.get(0).kind());
        b.cabRequest(0, 9);
        List<Served> all = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            all.addAll(b.step());
            ticker.advance();
        }
        assertEquals(List.of(9, 5), all.stream().map(Served::floor).toList());
        assertEquals(List.of(Served.Kind.CAB, Served.Kind.HALL_DOWN), all.stream().map(Served::kind).toList());
    }

    /** With nothing else to do, an UP and a DOWN call at one floor still never open together: each gets its own stop. */
    @Test
    void idleCarSplitsTwoCallsAtOneFloor() {
        Building b = new Building(10, new int[] {5}, new NearestDispatcher(), ticker);
        b.hallCall(5, Direction.UP);
        b.hallCall(5, Direction.DOWN);
        assertEquals(1, b.step().size());
        assertEquals(1, b.step().size());
        assertEquals(0, b.pending());
    }

    /** Pressing the same hall button twice gives the same car and one pending stop, even when another car is emptier. */
    @Test
    void duplicateHallCallGoesToTheSameCar() {
        Building b = new Building(10, 2, new LeastLoadedDispatcher(), ticker);
        HallResult first = b.hallCall(4, Direction.UP);
        HallResult again = b.hallCall(4, Direction.UP);
        assertEquals(first, again);
        assertEquals(1, b.pending());
    }

    /** A null direction is rejected like any other bad button, not treated as DOWN. */
    @Test
    void nullDirectionRejected() {
        Building b = new Building(10, 1, new NearestDispatcher(), ticker);
        assertThrows(IllegalArgumentException.class, () -> b.hallCall(0, null));
        assertThrows(IllegalArgumentException.class, () -> b.hallCall(3, null));
    }
}
