package com.lld.mistakes;

import java.util.Optional;

/**
 * <b>Bug (M9).</b> The old parking-lot review praised "proper {@code volatile} fields for status and vehicle". Each field is visible, but the <em>pair</em> is not atomic.
 * <p>Example: {@code park} sets {@code occupied = true}, then {@code vehicle = v}. A reader between the two writes sees "occupied by nobody".
 * <p>{@code park} is also a check-then-act, so two cars can both park (Topic 07 reproduces that race deterministically).
 */
public final class TwoVolatileSpot implements ParkingSpot {

    private volatile boolean occupied;
    private volatile String vehicle;
    private final Gap gap;

    /**
     * Creates a free spot. The gap sits between the two writes so a test can read in the middle.
     */
    public TwoVolatileSpot(Gap gap) {
        this.gap = gap;
    }

    /**
     * Sets the occupied flag, pauses in the gap, then sets the vehicle. A reader in the gap sees an occupied spot with no vehicle.
     */
    @Override
    public boolean park(String v) {
        if (occupied) {
            return false;
        }
        occupied = true;
        gap.pause();                                   // a reader here sees occupied && vehicle == null
        vehicle = v;
        return true;
    }

    /**
     * Returns the vehicle if the spot is flagged occupied. In the gap this is empty even though the spot is occupied.
     */
    @Override
    public Optional<String> occupant() {
        return occupied ? Optional.ofNullable(vehicle) : Optional.empty();
    }

    /**
     * Returns the occupied flag.
     */
    @Override
    public boolean isOccupied() {
        return occupied;
    }
}
