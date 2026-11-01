package com.lld.parkinglot;

import java.time.Instant;

/**
 * Proof that a vehicle is parked. The driver hands it back to leave.
 *
 * @param id unique ticket id
 * @param vehicle the parked vehicle
 * @param spotId the spot it was given, like {@code L0-MEDIUM-2} (level 0)
 * @param entry when the vehicle entered, read from the injected clock
 */
public record Ticket(String id, Vehicle vehicle, String spotId, Instant entry) {
}
