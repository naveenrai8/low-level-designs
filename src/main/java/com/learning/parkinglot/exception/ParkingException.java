package com.learning.parkinglot.exception;

/**
 * Base exception for parking lot related errors.
 */
public class ParkingException extends RuntimeException {

    public ParkingException(String message) {
        super(message);
    }

    public ParkingException(String message, Throwable cause) {
        super(message, cause);
    }
}
