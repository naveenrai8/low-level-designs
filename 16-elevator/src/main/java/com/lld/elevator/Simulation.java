package com.lld.elevator;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs riders through a {@link Building} in discrete virtual time: no threads, no wall clock.
 *
 * <p>Each tick: (1) riders who arrive now press their hall button, (2) the building steps, (3) riders waiting at a floor board the car that answered their button and press their destination, riders inside get out at their floor. A stop costs one tick (the car stays put with doors open); boarding itself takes no extra time. Cars have no capacity limit. A rider only boards the car their call was given to.
 */
public final class Simulation {

    /** A rider who has pressed a button and not yet boarded. */
    private record Waiting(Rider rider, int carId, Direction dir) {
    }

    /** A rider inside a car. */
    private record Onboard(Rider rider, int carId) {
    }

    /** Not instantiable: this class only holds static helpers. */
    private Simulation() {
    }

    /** Runs {@code riders} through a fresh building and returns the wait statistics. Throws if the riders are not all delivered within a generous tick limit. */
    public static SimResult run(String name, List<Rider> riders, int floors, int cars, Dispatcher dispatcher) {
        VirtualTicker ticker = new VirtualTicker();
        Building b = new Building(floors, cars, dispatcher, ticker);
        List<Waiting> waiting = new ArrayList<>();
        List<Onboard> onboard = new ArrayList<>();
        List<Long> waits = new ArrayList<>();
        int next = 0;
        int delivered = 0;
        long limit = (riders.isEmpty() ? 0 : riders.get(riders.size() - 1).arrival()) + 100_000L;
        while (delivered < riders.size()) {
            long now = ticker.read();
            if (now > limit) {
                throw new IllegalStateException("simulation did not finish");
            }
            while (next < riders.size() && riders.get(next).arrival() == now) {
                Rider r = riders.get(next++);
                Direction d = r.dest() > r.origin() ? Direction.UP : Direction.DOWN;
                HallResult res = b.hallCall(r.origin(), d);
                waiting.add(new Waiting(r, res.carId(), d));
            }
            for (Served s : b.step()) {
                if (s.kind() == Served.Kind.CAB) {
                    int before = onboard.size();
                    onboard.removeIf(o -> o.carId() == s.carId() && o.rider().dest() == s.floor());
                    delivered += before - onboard.size();
                } else if (s.kind() != Served.Kind.FIRE_RECALL) {
                    Direction d = s.kind() == Served.Kind.HALL_UP ? Direction.UP : Direction.DOWN;
                    List<Waiting> boarding = waiting.stream()
                            .filter(w -> w.carId() == s.carId() && w.rider().origin() == s.floor() && w.dir() == d).toList();
                    for (Waiting w : boarding) {
                        waits.add(s.tick() - w.rider().arrival());
                        onboard.add(new Onboard(w.rider(), s.carId()));
                        b.cabRequest(s.carId(), w.rider().dest());
                    }
                    waiting.removeAll(boarding);
                }
            }
            ticker.advance();
        }
        if (waits.isEmpty()) {
            return new SimResult(name, 0, 0, 0, 0, ticker.read());
        }
        long max = waits.stream().mapToLong(Long::longValue).max().orElseThrow();
        return new SimResult(name, riders.size(), Stats.mean(waits), Stats.percentileNearestRank(waits, 95), max, ticker.read());
    }
}
