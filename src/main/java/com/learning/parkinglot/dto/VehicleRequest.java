package com.learning.parkinglot.dto;

import com.learning.parkinglot.model.VehicleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for vehicle entry.
 *
 * @param licensePlate the vehicle's license plate
 * @param type the vehicle type
 */
public record VehicleRequest(
        @NotBlank(message = "License plate is required")
        String licensePlate,

        @NotNull(message = "Vehicle type is required")
        VehicleType type
) {
}
