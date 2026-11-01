package com.lld.elevator;

import java.util.ArrayList;
import java.util.List;

/**
 * A bank of cars plus a dispatcher. Implements {@link ElevatorSystem}.
 *
 * <p>Concurrency: one lock (the object's monitor) guards every car's queues, positions and modes, plus the fire flag. Dispatching reads car snapshots and then writes to one car, and that read-then-write must be one atomic step, otherwise two threads could both see the same "least loaded" car. Every public method is one short critical section; no I/O happens inside.
 */
public final class Building implements ElevatorSystem {

    private final int floors;
    private final List<Car> cars = new ArrayList<>();
    private final Dispatcher dispatcher;
    private final Ticker ticker;
    private boolean fire;

    /** Creates a building with {@code floors} floors (0 to floors-1) and one car per entry of {@code startFloors}. */
    public Building(int floors, int[] startFloors, Dispatcher dispatcher, Ticker ticker) {
        if (floors < 2 || startFloors.length < 1) {
            throw new IllegalArgumentException("need at least 2 floors and 1 car");
        }
        this.floors = floors;
        this.dispatcher = dispatcher;
        this.ticker = ticker;
        for (int i = 0; i < startFloors.length; i++) {
            checkFloor(startFloors[i]);
            cars.add(new Car(i, startFloors[i]));
        }
    }

    /** Creates a building where all {@code carCount} cars start at the lobby (floor 0). */
    public Building(int floors, int carCount, Dispatcher dispatcher, Ticker ticker) {
        this(floors, new int[carCount], dispatcher, ticker);
    }

    /** Rejects floors outside 0 to floors-1. */
    private void checkFloor(int f) {
        if (f < 0 || f >= floors) {
            throw new IllegalArgumentException("floor out of range: " + f);
        }
    }

    /** Rejects car ids that do not exist. */
    private Car car(int carId) {
        if (carId < 0 || carId >= cars.size()) {
            throw new IllegalArgumentException("unknown car: " + carId);
        }
        return cars.get(carId);
    }

    /** Hands a call to the dispatcher's choice among NORMAL cars, unless a NORMAL car already holds that exact button, in which case the call stays with it. Caller must hold the lock. */
    private HallResult dispatch(HallCall call) {
        for (Car c : cars) {
            if (c.mode() == CarMode.NORMAL && c.owns(call.floor(), call.direction())) {
                return new HallResult(HallResult.Status.ACCEPTED, c.id());
            }
        }
        List<CarView> eligible = new ArrayList<>();
        for (Car c : cars) {
            if (c.mode() == CarMode.NORMAL) {
                eligible.add(c.view());
            }
        }
        if (eligible.isEmpty()) {
            return new HallResult(HallResult.Status.NO_CAR_AVAILABLE, -1);
        }
        int id = dispatcher.pick(call, eligible);
        Car chosen = eligible.stream().anyMatch(v -> v.id() == id) ? cars.get(id) : null;
        if (chosen == null) {
            throw new IllegalStateException("dispatcher picked an ineligible car: " + id);
        }
        chosen.addHall(call.floor(), call.direction());
        return new HallResult(HallResult.Status.ACCEPTED, id);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized HallResult hallCall(int floor, Direction direction) {
        checkFloor(floor);
        if (direction == null || direction == Direction.IDLE || (direction == Direction.UP && floor == floors - 1) || (direction == Direction.DOWN && floor == 0)) {
            throw new IllegalArgumentException("no such button: " + direction + " at floor " + floor);
        }
        if (fire) {
            return new HallResult(HallResult.Status.REJECTED_FIRE, -1);
        }
        return dispatch(new HallCall(floor, direction));
    }

    /** {@inheritDoc} */
    @Override
    public synchronized boolean cabRequest(int carId, int floor) {
        checkFloor(floor);
        Car c = car(carId);
        if (c.mode() != CarMode.NORMAL) {
            return false;
        }
        c.addCab(floor);
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized List<Served> step() {
        long now = ticker.read();
        List<Served> all = new ArrayList<>();
        for (Car c : cars) {
            all.addAll(c.step(now));
        }
        return all;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void setFire(boolean on) {
        fire = on;
        for (Car c : cars) {
            if (on && c.mode() != CarMode.FIRE) {
                c.apply(ModeEvent.FIRE_ON);
            } else if (!on && c.mode() == CarMode.FIRE) {
                c.apply(ModeEvent.FIRE_OFF);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized List<HallCall> setMaintenance(int carId, boolean on) {
        Car c = car(carId);
        List<HallCall> released = c.apply(on ? ModeEvent.MAINTENANCE_ON : ModeEvent.MAINTENANCE_OFF);
        List<HallCall> orphaned = new ArrayList<>();
        for (HallCall call : released) {
            if (dispatch(call).status() != HallResult.Status.ACCEPTED) {
                orphaned.add(call);
            }
        }
        return orphaned;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized List<CarView> cars() {
        return cars.stream().map(Car::view).toList();
    }

    /** {@inheritDoc} */
    @Override
    public synchronized int pending() {
        return cars.stream().mapToInt(Car::pending).sum();
    }

    /** Returns the mode of one car (for tests and dashboards). */
    public synchronized CarMode mode(int carId) {
        return car(carId).mode();
    }
}
