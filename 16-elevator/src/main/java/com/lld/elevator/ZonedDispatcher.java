package com.lld.elevator;

import java.util.List;

/**
 * Cuts the building into one equal band of floors per car and always sends the band's owner. Car 0 owns the lowest band. The car count must match the building: a car id at or above it is rejected with IllegalArgumentException.
 *
 * <p>If the owner is out of service (maintenance or fire) it falls back to {@link NearestDispatcher}, so a call is never lost.
 */
public final class ZonedDispatcher implements Dispatcher {

    private final int floors;
    private final int cars;
    private final NearestDispatcher fallback = new NearestDispatcher();

    /** Creates a zoned dispatcher for a building with {@code floors} floors and {@code cars} cars. */
    public ZonedDispatcher(int floors, int cars) {
        if (floors < 2 || cars < 1) {
            throw new IllegalArgumentException("need at least 2 floors and 1 car");
        }
        this.floors = floors;
        this.cars = cars;
    }

    /** Returns which car owns the call's floor band, or the nearest car if the owner is not eligible. */
    @Override
    public int pick(HallCall call, List<CarView> eligible) {
        for (CarView c : eligible) {
            if (c.id() >= cars) {
                throw new IllegalArgumentException("car " + c.id() + " is outside the " + cars + " cars this dispatcher was built for");
            }
        }
        int zone = Math.min(cars - 1, call.floor() * cars / floors);
        for (CarView c : eligible) {
            if (c.id() == zone) {
                return zone;
            }
        }
        return fallback.pick(call, eligible);
    }
}
