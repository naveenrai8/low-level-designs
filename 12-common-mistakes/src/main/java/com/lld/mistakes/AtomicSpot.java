package com.lld.mistakes;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <b>Fix (M9).</b> Make the related fields one value. The occupant reference <em>is</em> the
 * state ({@code null} = free), so a single CAS both checks and acts, and no reader can see half a
 * state. For more fields, put them in an immutable record and CAS the record.
 */
public final class AtomicSpot implements ParkingSpot {

    private final AtomicReference<String> occupant = new AtomicReference<>();

    @Override
    public boolean park(String vehicle) {
        return occupant.compareAndSet(null, vehicle);
    }

    @Override
    public Optional<String> occupant() {
        return Optional.ofNullable(occupant.get());
    }

    @Override
    public boolean isOccupied() {
        return occupant.get() != null;
    }
}
