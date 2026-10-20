package com.lld.mistakes;

import java.util.Optional;

/**
 * <b>Bug (M9).</b> The old parking-lot review praised "proper {@code volatile} fields for status
 * and vehicle". Each field is visible, but the <em>pair</em> isn't atomic. A reader between the
 * two writes sees "occupied by nobody". {@code park} is also a check-then-act, so two cars can
 * both park (Topic 07 reproduces that race deterministically).
 */
public final class TwoVolatileSpot implements ParkingSpot {

    private volatile boolean occupied;
    private volatile String vehicle;
    private final Gap gap;

    public TwoVolatileSpot(Gap gap) {
        this.gap = gap;
    }

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

    @Override
    public Optional<String> occupant() {
        return occupied ? Optional.ofNullable(vehicle) : Optional.empty();
    }

    @Override
    public boolean isOccupied() {
        return occupied;
    }
}
