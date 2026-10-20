package com.learning.concert.model;

public class Seat {
    private final String seatId;
    private final double price;
    private volatile boolean booked;

    public Seat(String seatId, double price) {
        this.seatId = seatId;
        this.price = price;
        this.booked = false;
    }

    public synchronized boolean book() {
        if (!booked) {
            booked = true;
            return true;
        }
        return false;
    }

    public String getSeatId() { return seatId; }
    public double getPrice() { return price; }
    public boolean isBooked() { return booked; }
}
