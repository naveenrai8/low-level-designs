package com.learning.airline.model;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

public class Flight {
    private final String flightNumber;
    private final String source;
    private final String destination;
    private final LocalDateTime departureTime;
    private final int capacity;
    private final AtomicInteger bookedSeats;
    private final double price;

    public Flight(String flightNumber, String source, String destination,
                 LocalDateTime departureTime, int capacity, double price) {
        this.flightNumber = flightNumber;
        this.source = source;
        this.destination = destination;
        this.departureTime = departureTime;
        this.capacity = capacity;
        this.bookedSeats = new AtomicInteger(0);
        this.price = price;
    }

    public boolean bookSeat() {
        return bookedSeats.updateAndGet(current ->
            current < capacity ? current + 1 : current
        ) <= capacity;
    }

    public String getFlightNumber() { return flightNumber; }
    public String getSource() { return source; }
    public String getDestination() { return destination; }
    public LocalDateTime getDepartureTime() { return departureTime; }
    public int getCapacity() { return capacity; }
    public int getAvailableSeats() { return capacity - bookedSeats.get(); }
    public double getPrice() { return price; }
}
