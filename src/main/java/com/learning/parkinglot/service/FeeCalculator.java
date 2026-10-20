package com.learning.parkinglot.service;

import com.learning.parkinglot.model.Ticket;
import com.learning.parkinglot.model.VehicleType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calculator for parking fees based on vehicle type and duration.
 * Thread-safe implementation.
 */
@Component
public class FeeCalculator {
    private final Map<VehicleType, Double> hourlyRates;

    public FeeCalculator() {
        this.hourlyRates = new ConcurrentHashMap<>();
        initializeDefaultRates();
    }

    /**
     * Initializes default hourly rates for each vehicle type.
     */
    private void initializeDefaultRates() {
        hourlyRates.put(VehicleType.MOTORCYCLE, 5.0);  // $5/hour
        hourlyRates.put(VehicleType.CAR, 10.0);        // $10/hour
        hourlyRates.put(VehicleType.TRUCK, 20.0);      // $20/hour
    }

    /**
     * Calculates the parking fee for a given ticket and exit time.
     * Fee is calculated based on ceiling of hours parked.
     *
     * Time complexity: O(1)
     *
     * @param ticket the parking ticket
     * @param exitTime the exit timestamp
     * @return the calculated fee
     */
    public double calculateFee(Ticket ticket, Instant exitTime) {
        Duration duration = Duration.between(ticket.entryTime(), exitTime);

        // Calculate hours, rounding up (ceiling)
        long minutes = duration.toMinutes();
        long hours = (long) Math.ceil(minutes / 60.0);

        // Minimum charge is for 1 hour
        if (hours < 1) {
            hours = 1;
        }

        Double hourlyRate = hourlyRates.get(ticket.vehicleType());
        if (hourlyRate == null) {
            throw new IllegalArgumentException("Unknown vehicle type: " + ticket.vehicleType());
        }

        return hours * hourlyRate;
    }

    /**
     * Updates the hourly rate for a vehicle type.
     *
     * @param vehicleType the vehicle type
     * @param rate the new hourly rate
     */
    public void setHourlyRate(VehicleType vehicleType, double rate) {
        if (rate < 0) {
            throw new IllegalArgumentException("Rate cannot be negative");
        }
        hourlyRates.put(vehicleType, rate);
    }

    /**
     * Gets the hourly rate for a vehicle type.
     *
     * @param vehicleType the vehicle type
     * @return the hourly rate
     */
    public double getHourlyRate(VehicleType vehicleType) {
        return hourlyRates.getOrDefault(vehicleType, 0.0);
    }
}
