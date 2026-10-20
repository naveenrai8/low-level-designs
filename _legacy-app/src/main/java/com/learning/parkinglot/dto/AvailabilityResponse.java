package com.learning.parkinglot.dto;

import com.learning.parkinglot.model.VehicleType;

import java.time.Instant;
import java.util.Map;

/**
 * Response DTO for parking availability.
 *
 * @param availability map of vehicle type to available count
 * @param timestamp the timestamp of the query
 */
public record AvailabilityResponse(
        Map<VehicleType, Long> availability,
        Instant timestamp
) {
    public static AvailabilityResponse of(Map<VehicleType, Long> availability) {
        return new AvailabilityResponse(availability, Instant.now());
    }
}
