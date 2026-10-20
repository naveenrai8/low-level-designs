package com.learning.hotel.model;

public class Room {
    private final String roomNumber;
    private final RoomType type;
    private final double pricePerNight;
    private volatile RoomStatus status;

    public Room(String roomNumber, RoomType type, double pricePerNight) {
        this.roomNumber = roomNumber;
        this.type = type;
        this.pricePerNight = pricePerNight;
        this.status = RoomStatus.AVAILABLE;
    }

    public synchronized boolean book() {
        if (status == RoomStatus.AVAILABLE) {
            status = RoomStatus.BOOKED;
            return true;
        }
        return false;
    }

    public synchronized void checkout() {
        status = RoomStatus.AVAILABLE;
    }

    public String getRoomNumber() { return roomNumber; }
    public RoomType getType() { return type; }
    public double getPricePerNight() { return pricePerNight; }
    public RoomStatus getStatus() { return status; }
}
