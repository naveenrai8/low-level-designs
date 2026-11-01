package com.lld.elevator;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * One car and its LOOK scheduler. Not thread-safe: the {@link Building} serialises access.
 *
 * <p>LOOK in one sentence: keep going the way you are going while any request is ahead, stop at every request in that direction, turn around only when nothing is ahead. (SCAN would run on to the end of the shaft first.)
 *
 * <p>Three sorted sets hold the work: cab presses, up-button calls and down-button calls. A hall call is stopped for when the car goes the call's way, or when the car is about to turn right there because nothing is ahead.
 */
final class Car {

    private final int id;
    private int floor;
    private Direction dir;
    private CarMode mode = CarMode.NORMAL;
    private CarMode modeBeforeFire = CarMode.NORMAL;
    private boolean fireDoorsOpened;
    private final TreeSet<Integer> cab = new TreeSet<>();
    private final TreeSet<Integer> up = new TreeSet<>();
    private final TreeSet<Integer> down = new TreeSet<>();

    /** Creates an idle car at {@code floor}. */
    Car(int id, int floor) {
        this(id, floor, Direction.IDLE);
    }

    /** Creates a car at {@code floor} already heading {@code dir}. Tests use this to start mid-trip. */
    Car(int id, int floor, Direction dir) {
        this.id = id;
        this.floor = floor;
        this.dir = dir;
    }

    /** Returns the car id. */
    int id() {
        return id;
    }

    /** Returns the floor the car is at. */
    int floor() {
        return floor;
    }

    /** Returns the current travel direction. */
    Direction direction() {
        return dir;
    }

    /** Returns the current mode. */
    CarMode mode() {
        return mode;
    }

    /** Returns how many stops are waiting (cab and hall). */
    int pending() {
        return cab.size() + up.size() + down.size();
    }

    /** Returns true if any pending stop is on a higher floor than the car. */
    boolean hasAbove() {
        return nextAbove() != null;
    }

    /** Returns true if any pending stop is on a lower floor than the car. */
    boolean hasBelow() {
        return nextBelow() != null;
    }

    /** Returns a snapshot for dispatchers. */
    CarView view() {
        return new CarView(id, floor, dir, pending());
    }

    /** Adds a rider's floor press. */
    void addCab(int target) {
        cab.add(target);
    }

    /** Adds a hall call. Adding the same call twice keeps one. */
    void addHall(int target, Direction d) {
        (d == Direction.UP ? up : down).add(target);
    }

    /**
     * Runs one tick. A tick is either a stop or a move, never both.
     *
     * <p>Stop: if anything applies at this floor, the doors open, the car stays put, and the stops are returned. This gives the rider time to press a destination before the car chooses where to go. Move: otherwise pick a direction by the LOOK rule and travel one floor.
     *
     * <p>In fire mode the car drives one floor towards the lobby and, on arrival, opens its doors once (a FIRE_RECALL stop). In maintenance it does nothing. Riders still inside a car in maintenance are not unloaded: this model assumes a technician takes an empty car.
     */
    List<Served> step(long now) {
        if (mode == CarMode.MAINTENANCE) {
            return List.of();
        }
        if (mode == CarMode.FIRE) {
            if (floor > 0) {
                floor--;
                dir = floor > 0 ? Direction.DOWN : Direction.IDLE;
                return List.of();
            }
            if (!fireDoorsOpened) {
                fireDoorsOpened = true;
                return List.of(new Served(id, 0, Served.Kind.FIRE_RECALL, now));
            }
            return List.of();
        }
        List<Served> served = serveHere(now);
        if (!served.isEmpty()) {
            return served;
        }
        dir = chooseDirection();
        if (dir == Direction.UP) {
            floor++;
        } else if (dir == Direction.DOWN) {
            floor--;
        }
        return served;
    }

    /** Returns true if this car already holds the hall call, so a second press of the same button can be given to the same car. */
    boolean owns(int target, Direction d) {
        return (d == Direction.UP ? up : down).contains(target);
    }

    /** Applies a mode event through the table. A car that was in maintenance when the fire started goes back to maintenance when the fire clears, not to NORMAL. Returns the hall calls this car gives up (all dropped for fire, to be re-homed for maintenance). Cab presses are always dropped. */
    List<HallCall> apply(ModeEvent event) {
        CarMode next = ModeTable.next(mode, event);
        if (event == ModeEvent.FIRE_ON) {
            modeBeforeFire = mode;
            fireDoorsOpened = false;
        } else if (event == ModeEvent.FIRE_OFF && modeBeforeFire == CarMode.MAINTENANCE) {
            next = CarMode.MAINTENANCE;
        }
        mode = next;
        List<HallCall> released = new ArrayList<>();
        if (mode != CarMode.NORMAL) {
            up.forEach(f -> released.add(new HallCall(f, Direction.UP)));
            down.forEach(f -> released.add(new HallCall(f, Direction.DOWN)));
            cab.clear();
            up.clear();
            down.clear();
            dir = mode == CarMode.FIRE && floor > 0 ? Direction.DOWN : Direction.IDLE;
        } else {
            dir = Direction.IDLE;
        }
        return released;
    }

    /**
     * Decides what the doors open for at the current floor and removes it from the queues.
     *
     * <p>A cab press here is always served. At most one hall call is served per tick, so a rider is never dragged the wrong way: the call matching the travel direction wins (UP when idle); the opposite call is served only when nothing is ahead, which means the car is turning here. The car then commits to the served call's direction, so the rider's destination press (which arrives after this tick) is compared against the right direction.
     */
    private List<Served> serveHere(long now) {
        boolean nothingAhead = dir == Direction.UP ? !hasAbove() : dir == Direction.DOWN ? !hasBelow() : true;
        List<Served> served = new ArrayList<>();
        if (cab.remove(floor)) {
            served.add(new Served(id, floor, Served.Kind.CAB, now));
        }
        Direction first = dir == Direction.DOWN ? Direction.DOWN : Direction.UP;
        Direction second = first == Direction.UP ? Direction.DOWN : Direction.UP;
        if (hall(first).remove(floor)) {
            served.add(new Served(id, floor, kindOf(first), now));
            dir = first;
        } else if (nothingAhead && hall(second).remove(floor)) {
            served.add(new Served(id, floor, kindOf(second), now));
            dir = second;
        }
        return served;
    }

    /** Returns the queue of hall calls for one direction. */
    private TreeSet<Integer> hall(Direction d) {
        return d == Direction.UP ? up : down;
    }

    /** Maps a hall direction to the stop kind reported to callers. */
    private static Served.Kind kindOf(Direction d) {
        return d == Direction.UP ? Served.Kind.HALL_UP : Served.Kind.HALL_DOWN;
    }

    /** Applies the LOOK rule: keep direction while work is ahead, else turn if work is behind, else idle. An idle car heads for the nearest work. */
    private Direction chooseDirection() {
        boolean above = hasAbove();
        boolean below = hasBelow();
        return switch (dir) {
            case UP -> above ? Direction.UP : below ? Direction.DOWN : Direction.IDLE;
            case DOWN -> below ? Direction.DOWN : above ? Direction.UP : Direction.IDLE;
            case IDLE -> {
                if (above && below) {
                    yield nextAbove() - floor <= floor - nextBelow() ? Direction.UP : Direction.DOWN;
                }
                yield above ? Direction.UP : below ? Direction.DOWN : Direction.IDLE;
            }
        };
    }

    /** Returns the lowest pending floor above the car, or null. */
    private Integer nextAbove() {
        Integer best = null;
        for (TreeSet<Integer> s : List.of(cab, up, down)) {
            Integer h = s.higher(floor);
            if (h != null && (best == null || h < best)) {
                best = h;
            }
        }
        return best;
    }

    /** Returns the highest pending floor below the car, or null. */
    private Integer nextBelow() {
        Integer best = null;
        for (TreeSet<Integer> s : List.of(cab, up, down)) {
            Integer l = s.lower(floor);
            if (l != null && (best == null || l > best)) {
                best = l;
            }
        }
        return best;
    }
}
