package com.lld.mistakes;

import java.util.Optional;

/**
 * A spot is either free, or occupied by exactly one vehicle. Never "occupied by nobody".
 */
public interface ParkingSpot {

    /**
     * Tries to park the vehicle.
     * 
     * @return {@code true} if this caller parked; {@code false} if the spot was taken.
     */
    boolean park(String vehicle);

    /**
     * Says who is parked.
     * 
     * @return the occupant, if the spot is occupied.
     */
    Optional<String> occupant();

    /**
     * True if a vehicle is parked.
     */
    boolean isOccupied();
}
