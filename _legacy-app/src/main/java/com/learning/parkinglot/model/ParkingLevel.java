package com.learning.parkinglot.model;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Represents a level in the parking lot containing multiple parking spots.
 * Thread-safe implementation using ConcurrentHashMap.
 */
public class ParkingLevel {
    private final int levelNumber;
    private final ConcurrentHashMap<String, ParkingSpot> spots;

    public ParkingLevel(int levelNumber) {
        this.levelNumber = levelNumber;
        this.spots = new ConcurrentHashMap<>();
    }

    /**
     * Adds a parking spot to this level.
     *
     * @param spot the spot to add
     */
    public void addSpot(ParkingSpot spot) {
        spots.put(spot.getSpotId(), spot);
    }

    /**
     * Finds an available spot for the given vehicle type.
     * Thread-safe operation.
     *
     * @param vehicleType the type of vehicle
     * @return Optional containing the spot if available, empty otherwise
     */
    public Optional<ParkingSpot> findAvailableSpot(VehicleType vehicleType) {
        return spots.values().stream()
                .filter(spot -> spot.getAllowedType() == vehicleType)
                .filter(ParkingSpot::isAvailable)
                .findFirst();
    }

    /**
     * Gets the count of available spots grouped by vehicle type.
     * Thread-safe operation.
     *
     * @return Map of vehicle type to available count
     */
    public Map<VehicleType, Long> getAvailableCount() {
        return spots.values().stream()
                .filter(ParkingSpot::isAvailable)
                .collect(Collectors.groupingBy(
                        ParkingSpot::getAllowedType,
                        Collectors.counting()
                ));
    }

    /**
     * Gets total number of spots on this level.
     *
     * @return total spot count
     */
    public int getTotalSpots() {
        return spots.size();
    }

    public int getLevelNumber() {
        return levelNumber;
    }

    public Map<String, ParkingSpot> getSpots() {
        return Map.copyOf(spots); // Return immutable copy
    }

    @Override
    public String toString() {
        return "ParkingLevel{" +
                "levelNumber=" + levelNumber +
                ", totalSpots=" + spots.size() +
                '}';
    }
}
