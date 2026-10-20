package com.learning.airline.model;

import java.util.UUID;

public class FlightBooking {
    private final String bookingId;
    private final String flightNumber;
    private final String passengerName;
    private final int numberOfSeats;
    private final double totalCost;
    private volatile BookingStatus status;

    public FlightBooking(String flightNumber, String passengerName, int numberOfSeats, double totalCost) {
        this.bookingId = UUID.randomUUID().toString();
        this.flightNumber = flightNumber;
        this.passengerName = passengerName;
        this.numberOfSeats = numberOfSeats;
        this.totalCost = totalCost;
        this.status = BookingStatus.CONFIRMED;
    }

    public synchronized void cancel() {
        status = BookingStatus.CANCELLED;
    }

    public String getBookingId() { return bookingId; }
    public String getFlightNumber() { return flightNumber; }
    public String getPassengerName() { return passengerName; }
    public int getNumberOfSeats() { return numberOfSeats; }
    public double getTotalCost() { return totalCost; }
    public BookingStatus getStatus() { return status; }
}
