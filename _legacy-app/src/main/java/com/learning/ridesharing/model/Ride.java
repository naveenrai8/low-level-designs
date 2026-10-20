package com.learning.ridesharing.model;

import java.time.Instant;

public class Ride {
    private final String rideId;
    private final String passengerId;
    private String driverId;
    private final Location pickup;
    private final Location destination;
    private final RideType type;
    private volatile RideStatus status;
    private final Instant requestTime;
    private double fare;

    public Ride(String passengerId, Location pickup, Location destination, RideType type) {
        this.rideId = java.util.UUID.randomUUID().toString();
        this.passengerId = passengerId;
        this.pickup = pickup;
        this.destination = destination;
        this.type = type;
        this.status = RideStatus.REQUESTED;
        this.requestTime = Instant.now();
    }

    public synchronized void assignDriver(String driverId) {
        this.driverId = driverId;
        this.status = RideStatus.DRIVER_ASSIGNED;
    }

    public synchronized void startRide() {
        this.status = RideStatus.IN_PROGRESS;
    }

    public synchronized void completeRide(double fare) {
        this.fare = fare;
        this.status = RideStatus.COMPLETED;
    }

    public String getRideId() { return rideId; }
    public RideStatus getStatus() { return status; }
    public Location getPickup() { return pickup; }
    public double getFare() { return fare; }
}

record Location(double latitude, double longitude) {}
