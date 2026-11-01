package com.lld.mistakes;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <b>Fix (M9).</b> Make the related fields one value. The occupant reference <em>is</em> the state ({@code null} = free), so a single CAS both checks and acts, and no reader can see half a state.
 * <p>For more fields, put them in an immutable record and CAS the record.
 */
public final class AtomicSpot implements ParkingSpot {

    private final AtomicReference<String> occupant = new AtomicReference<>();

    /**
     * Parks the vehicle if the spot is free. One compare-and-set checks and acts together, so only one caller can win.
     */
    @Override
    public boolean park(String vehicle) {
        return occupant.compareAndSet(null, vehicle);
    }

    /**
     * Returns the parked vehicle, or empty if the spot is free.
     */
    @Override
    public Optional<String> occupant() {
        return Optional.ofNullable(occupant.get());
    }

    /**
     * True if a vehicle is parked. Reads the same single reference as {@code occupant}, so the two always agree.
     */
    @Override
    public boolean isOccupied() {
        return occupant.get() != null;
    }
}
