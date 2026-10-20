package com.learning.parkinglot.service;

import com.learning.parkinglot.model.Ticket;
import com.learning.parkinglot.model.Vehicle;
import com.learning.parkinglot.model.VehicleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for FeeCalculator.
 * Uses table-driven tests for various duration scenarios.
 */
class FeeCalculatorTest {

    private FeeCalculator feeCalculator;

    @BeforeEach
    void setUp() {
        feeCalculator = new FeeCalculator();
    }

    @ParameterizedTest
    @CsvSource({
            "MOTORCYCLE, 30, 5.0",    // 30 minutes -> 1 hour minimum
            "MOTORCYCLE, 60, 5.0",    // 1 hour
            "MOTORCYCLE, 90, 10.0",   // 1.5 hours -> ceil to 2 hours
            "MOTORCYCLE, 120, 10.0",  // 2 hours
            "CAR, 30, 10.0",          // 30 minutes -> 1 hour minimum
            "CAR, 60, 10.0",          // 1 hour
            "CAR, 90, 20.0",          // 1.5 hours -> ceil to 2 hours
            "CAR, 180, 30.0",         // 3 hours
            "TRUCK, 30, 20.0",        // 30 minutes -> 1 hour minimum
            "TRUCK, 60, 20.0",        // 1 hour
            "TRUCK, 120, 40.0"        // 2 hours
    })
    @DisplayName("Calculate fee for various durations")
    void testCalculateFee(VehicleType vehicleType, long minutes, double expectedFee) {
        // Given
        Instant entryTime = Instant.now();
        Instant exitTime = entryTime.plus(minutes, ChronoUnit.MINUTES);

        Vehicle vehicle = new Vehicle("ABC123", vehicleType);
        Ticket ticket = Ticket.forEntry("TKT-1", vehicle, "L1-CAR-1", entryTime);

        // When
        double actualFee = feeCalculator.calculateFee(ticket, exitTime);

        // Then
        assertThat(actualFee).isEqualTo(expectedFee);
    }

    @Test
    @DisplayName("Minimum charge is 1 hour even for short durations")
    void testMinimumCharge() {
        // Given
        Instant entryTime = Instant.now();
        Instant exitTime = entryTime.plus(5, ChronoUnit.MINUTES);

        Vehicle vehicle = new Vehicle("ABC123", VehicleType.CAR);
        Ticket ticket = Ticket.forEntry("TKT-1", vehicle, "L1-CAR-1", entryTime);

        // When
        double fee = feeCalculator.calculateFee(ticket, exitTime);

        // Then
        assertThat(fee).isEqualTo(10.0); // 1 hour minimum for CAR
    }

    @Test
    @DisplayName("Get hourly rate for vehicle type")
    void testGetHourlyRate() {
        assertThat(feeCalculator.getHourlyRate(VehicleType.MOTORCYCLE)).isEqualTo(5.0);
        assertThat(feeCalculator.getHourlyRate(VehicleType.CAR)).isEqualTo(10.0);
        assertThat(feeCalculator.getHourlyRate(VehicleType.TRUCK)).isEqualTo(20.0);
    }

    @Test
    @DisplayName("Update hourly rate")
    void testSetHourlyRate() {
        // When
        feeCalculator.setHourlyRate(VehicleType.CAR, 15.0);

        // Then
        assertThat(feeCalculator.getHourlyRate(VehicleType.CAR)).isEqualTo(15.0);
    }

    @Test
    @DisplayName("Cannot set negative rate")
    void testNegativeRate() {
        assertThatThrownBy(() -> feeCalculator.setHourlyRate(VehicleType.CAR, -10.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Rate cannot be negative");
    }
}
