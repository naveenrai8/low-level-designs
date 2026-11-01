package com.lld.parkinglot;

import java.util.Objects;

/**
 * A vehicle, identified by its licence plate.
 *
 * @param plate the licence plate; two vehicles with the same plate are the same vehicle
 * @param type what kind of vehicle it is, which decides the spots it may use and the price
 */
public record Vehicle(String plate, VehicleType type) {

    /** Rejects a missing or blank plate and a missing type, so no half-built vehicle exists. */
    public Vehicle {
        Objects.requireNonNull(type, "type");
        if (plate == null || plate.isBlank()) {
            throw new IllegalArgumentException("plate must not be blank");
        }
    }
}
