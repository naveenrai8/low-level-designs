package com.learning.parkinglot.exception;

/**
 * Exception thrown when no parking spot is available for a vehicle.
 */
public class NoSpotAvailableException extends ParkingException {

    public NoSpotAvailableException(String message) {
        super(message);
    }
}
