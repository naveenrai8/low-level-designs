package com.learning.parkinglot.model;

/**
 * Enum representing the types of vehicles supported by the parking lot.
 * Each vehicle type requires a specific spot size.
 */
public enum VehicleType {
    /**
     * Motorcycle - smallest vehicle type, can fit in compact spots
     */
    MOTORCYCLE,

    /**
     * Car - standard vehicle type, requires standard spots
     */
    CAR,

    /**
     * Truck - largest vehicle type, requires large spots
     */
    TRUCK
}
