package com.learning.parkinglot.model;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Represents a parking spot in the parking lot.
 * Thread-safe implementation using ReentrantLock for state changes.
 */
public class ParkingSpot {
    private final String spotId;
    private final VehicleType allowedType;
    private final int level;
    private final ReentrantLock lock;

    private volatile SpotStatus status;
    private volatile Vehicle currentVehicle;

    public ParkingSpot(String spotId, VehicleType allowedType, int level) {
        this.spotId = spotId;
        this.allowedType = allowedType;
        this.level = level;
        this.status = SpotStatus.AVAILABLE;
        this.currentVehicle = null;
        this.lock = new ReentrantLock();
    }

    /**
     * Checks if the spot is available for parking.
     *
     * @return true if spot is available, false otherwise
     */
    public boolean isAvailable() {
        return status == SpotStatus.AVAILABLE;
    }

    /**
     * Occupies the spot with the given vehicle.
     * Thread-safe operation using lock.
     *
     * @param vehicle the vehicle to park
     * @throws IllegalStateException if spot is already occupied
     */
    public void occupy(Vehicle vehicle) {
        lock.lock();
        try {
            if (status == SpotStatus.OCCUPIED) {
                throw new IllegalStateException("Spot " + spotId + " is already occupied");
            }
            this.currentVehicle = vehicle;
            this.status = SpotStatus.OCCUPIED;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Releases the spot, making it available.
     * Thread-safe operation using lock.
     */
    public void release() {
        lock.lock();
        try {
            this.currentVehicle = null;
            this.status = SpotStatus.AVAILABLE;
        } finally {
            lock.unlock();
        }
    }

    // Getters
    public String getSpotId() {
        return spotId;
    }

    public VehicleType getAllowedType() {
        return allowedType;
    }

    public int getLevel() {
        return level;
    }

    public SpotStatus getStatus() {
        return status;
    }

    public Vehicle getCurrentVehicle() {
        return currentVehicle;
    }

    @Override
    public String toString() {
        return "ParkingSpot{" +
                "spotId='" + spotId + '\'' +
                ", allowedType=" + allowedType +
                ", level=" + level +
                ", status=" + status +
                '}';
    }
}
