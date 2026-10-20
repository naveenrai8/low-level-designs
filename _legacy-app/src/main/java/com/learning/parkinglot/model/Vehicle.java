package com.learning.parkinglot.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Record representing a vehicle.
 * Immutable DTO using Java 21 records feature.
 *
 * @param licensePlate unique identifier for the vehicle
 * @param type the type of vehicle (MOTORCYCLE, CAR, TRUCK)
 */
public record Vehicle(
        @NotBlank(message = "License plate cannot be blank")
        String licensePlate,

        @NotNull(message = "Vehicle type cannot be null")
        VehicleType type
) {
    /**
     * Compact constructor for validation.
     */
    public Vehicle {
        if (licensePlate != null) {
            licensePlate = licensePlate.trim().toUpperCase();
        }
    }
}
