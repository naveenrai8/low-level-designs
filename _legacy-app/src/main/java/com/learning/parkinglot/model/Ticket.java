package com.learning.parkinglot.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Record representing a parking ticket.
 * Immutable DTO using Java 21 records feature.
 *
 * @param ticketId unique identifier for the ticket
 * @param vehicleLicensePlate license plate of the parked vehicle
 * @param vehicleType type of the vehicle
 * @param spotId ID of the assigned parking spot
 * @param entryTime timestamp when vehicle entered
 * @param exitTime timestamp when vehicle exited (null if still parked)
 * @param fee parking fee (0.0 if not yet calculated)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Ticket(
        String ticketId,
        String vehicleLicensePlate,
        VehicleType vehicleType,
        String spotId,
        Instant entryTime,
        Instant exitTime,
        double fee
) {
    /**
     * Creates a new ticket for entry (without exit time and fee).
     */
    public static Ticket forEntry(String ticketId, Vehicle vehicle, String spotId, Instant entryTime) {
        return new Ticket(ticketId, vehicle.licensePlate(), vehicle.type(), spotId, entryTime, null, 0.0);
    }

    /**
     * Creates a ticket with exit information.
     */
    public Ticket withExit(Instant exitTime, double fee) {
        return new Ticket(ticketId, vehicleLicensePlate, vehicleType, spotId, entryTime, exitTime, fee);
    }
}
