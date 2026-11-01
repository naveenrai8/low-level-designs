package com.lld.elevator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Proves the LOOK rules on one car with explicit step tables. */
class LookTest {

    /** Steps the car until it has made {@code stops} stops (or 200 ticks) and returns the floors in order. */
    private static List<Integer> floorsServed(Car car, int stops) {
        List<Integer> out = new ArrayList<>();
        for (long t = 0; t < 200 && out.size() < stops; t++) {
            car.step(t).forEach(s -> out.add(s.floor()));
        }
        return out;
    }

    /** Car at 5 going up with cab presses 7, 3, 9 serves 7, then 9, then turns for 3. */
    @Test
    void stepTableUpThenDown() {
        Car car = new Car(0, 5, Direction.UP);
        car.addCab(7);
        car.addCab(3);
        car.addCab(9);
        assertEquals(List.of(7, 9, 3), floorsServed(car, 3));
    }

    /** The exact floor and direction after each tick of the 7, 3, 9 trip. A stop is its own tick (floor unchanged), so ticks 2, 5 and 12 are stops. */
    @Test
    void positionTableTickByTick() {
        Car car = new Car(0, 5, Direction.UP);
        car.addCab(7);
        car.addCab(3);
        car.addCab(9);
        int[] floors = {6, 7, 7, 8, 9, 9, 8, 7, 6, 5, 4, 3, 3};
        Direction up = Direction.UP;
        Direction dn = Direction.DOWN;
        Direction[] dirs = {up, up, up, up, up, up, dn, dn, dn, dn, dn, dn, dn};
        int[] stopTicks = {2, 5, 12};
        int stopsSeen = 0;
        for (int i = 0; i < floors.length; i++) {
            List<Served> s = car.step(i);
            assertEquals(floors[i], car.floor(), "floor after tick " + i);
            assertEquals(dirs[i], car.direction(), "direction after tick " + i);
            if (!s.isEmpty()) {
                assertEquals(stopTicks[stopsSeen++], i, "stop tick");
            }
        }
        assertEquals(3, stopsSeen);
        car.step(13);
        assertEquals(Direction.IDLE, car.direction());
    }

    /** A request that arrives mid-trip for a floor still ahead in the travel direction is served on the way. */
    @Test
    void midTripRequestAheadIsServedOnTheWay() {
        Car car = new Car(0, 2, Direction.UP);
        car.addCab(9);
        car.step(0);
        car.step(1);
        assertEquals(4, car.floor());
        car.addCab(6);
        assertEquals(List.of(6, 9), floorsServed(car, 2));
    }

    /** A request for a floor already passed is not served until the car has finished everything ahead. */
    @Test
    void passedFloorWaitsUntilTurn() {
        Car car = new Car(0, 2, Direction.UP);
        car.addCab(9);
        for (int t = 0; t < 4; t++) {
            car.step(t);
        }
        assertEquals(6, car.floor());
        car.addCab(4);
        assertEquals(List.of(9, 4), floorsServed(car, 2));
    }

    /** LOOK turns at the last request, not at the end of the shaft (that would be SCAN). */
    @Test
    void turnsAtLastRequestNotAtTheTop() {
        Car car = new Car(0, 4, Direction.UP);
        car.addCab(6);
        car.addCab(2);
        car.step(0);
        car.step(1);
        assertEquals(6, car.floor());
        assertEquals(1, car.step(2).size());
        assertEquals(6, car.floor());
        car.step(3);
        assertEquals(Direction.DOWN, car.direction());
        assertEquals(5, car.floor());
    }

    /** Randomised invariant with cab and hall presses: a car never reverses while work is strictly ahead, and it only moves towards work that really exists (a SCAN car would fail this). */
    @Test
    void neverReversesAndNeverMovesWithoutWork() {
        Random rnd = new Random(1);
        for (int trial = 0; trial < 200; trial++) {
            Car car = new Car(0, rnd.nextInt(10));
            for (int t = 0; t < 120; t++) {
                int roll = rnd.nextInt(8);
                if (roll == 0) {
                    car.addCab(rnd.nextInt(10));
                } else if (roll == 1) {
                    int f = rnd.nextInt(9);
                    car.addHall(f, Direction.UP);
                } else if (roll == 2) {
                    int f = 1 + rnd.nextInt(9);
                    car.addHall(f, Direction.DOWN);
                }
                Direction before = car.direction();
                int floorBefore = car.floor();
                boolean above = car.hasAbove();
                boolean below = car.hasBelow();
                car.step(t);
                if (before == Direction.UP && above) {
                    assertEquals(Direction.UP, car.direction(), "reversed with work above");
                }
                if (before == Direction.DOWN && below) {
                    assertEquals(Direction.DOWN, car.direction(), "reversed with work below");
                }
                if (car.floor() > floorBefore) {
                    assertTrue(above, "moved up with nothing above");
                }
                if (car.floor() < floorBefore) {
                    assertTrue(below, "moved down with nothing below");
                }
            }
        }
    }

    /** An up call at 4 is skipped by a car passing 4 going down, and answered after the car turns. */
    @Test
    void upCallIsNotServedByCarGoingDown() {
        Car car = new Car(0, 8, Direction.DOWN);
        car.addCab(1);
        car.addHall(4, Direction.UP);
        assertEquals(List.of(1, 4), floorsServed(car, 2));
    }

    /** A down call at 4 is served by a car passing 4 going down. */
    @Test
    void downCallIsServedByCarGoingDown() {
        Car car = new Car(0, 8, Direction.DOWN);
        car.addCab(1);
        car.addHall(4, Direction.DOWN);
        assertEquals(List.of(4, 1), floorsServed(car, 2));
    }

    /** An up call at 4 IS served by a car going down when nothing is below 4: the car turns right there. */
    @Test
    void upCallServedWhenCarTurnsAtThatFloor() {
        Car car = new Car(0, 8, Direction.DOWN);
        car.addHall(4, Direction.UP);
        List<Served> stops = new ArrayList<>();
        for (long t = 0; t < 10; t++) {
            stops.addAll(car.step(t));
        }
        assertEquals(1, stops.size());
        assertEquals(4, stops.get(0).floor());
        assertEquals(Served.Kind.HALL_UP, stops.get(0).kind());
    }

    /** An idle car with work on both sides heads for the nearer one. */
    @Test
    void idleCarGoesToNearestWork() {
        Car car = new Car(0, 5);
        car.addCab(4);
        car.addCab(9);
        assertEquals(List.of(4, 9), floorsServed(car, 2));
    }

    /** When both sides are equally far, an idle car goes up first. */
    @Test
    void idleCarTieGoesUp() {
        Car car = new Car(0, 5);
        car.addCab(3);
        car.addCab(7);
        assertEquals(List.of(7, 3), floorsServed(car, 2));
    }

    /** Duplicate presses collapse into one stop. */
    @Test
    void duplicatePressesAreOneStop() {
        Car car = new Car(0, 0);
        car.addCab(3);
        car.addCab(3);
        car.addHall(3, Direction.DOWN);
        car.addHall(3, Direction.DOWN);
        assertEquals(List.of(3, 3), floorsServed(car, 2));
        assertEquals(0, car.pending());
    }
}
