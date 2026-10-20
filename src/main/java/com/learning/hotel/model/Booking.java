package com.learning.hotel.model;

import java.time.LocalDate;
import java.util.UUID;

public class Booking {
    private final String bookingId;
    private final String guestName;
    private final String roomNumber;
    private final LocalDate checkInDate;
    private final LocalDate checkOutDate;
    private final double totalCost;
    private volatile BookingStatus status;

    public Booking(String guestName, String roomNumber, LocalDate checkInDate,
                   LocalDate checkOutDate, double totalCost) {
        this.bookingId = UUID.randomUUID().toString();
        this.guestName = guestName;
        this.roomNumber = roomNumber;
        this.checkInDate = checkInDate;
        this.checkOutDate = checkOutDate;
        this.totalCost = totalCost;
        this.status = BookingStatus.CONFIRMED;
    }

    public synchronized void cancel() {
        status = BookingStatus.CANCELLED;
    }

    public synchronized void complete() {
        status = BookingStatus.COMPLETED;
    }

    public String getBookingId() { return bookingId; }
    public String getGuestName() { return guestName; }
    public String getRoomNumber() { return roomNumber; }
    public LocalDate getCheckInDate() { return checkInDate; }
    public LocalDate getCheckOutDate() { return checkOutDate; }
    public double getTotalCost() { return totalCost; }
    public BookingStatus getStatus() { return status; }
}
