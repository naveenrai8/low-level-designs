package com.learning.parkinglot.exception;

/**
 * Exception thrown when an invalid ticket is provided.
 */
public class InvalidTicketException extends ParkingException {

    public InvalidTicketException(String message) {
        super(message);
    }
}
