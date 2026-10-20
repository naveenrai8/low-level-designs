package com.learning.parkinglot.service;

import com.learning.parkinglot.exception.InvalidTicketException;
import com.learning.parkinglot.exception.NoSpotAvailableException;
import com.learning.parkinglot.model.Ticket;
import com.learning.parkinglot.model.Vehicle;
import com.learning.parkinglot.model.VehicleType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for ParkingLotService.
 */
class ParkingLotServiceTest {

    private ParkingLotService parkingLotService;

    @BeforeEach
    void setUp() {
        FeeCalculator feeCalculator = new FeeCalculator();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        parkingLotService = new ParkingLotService(feeCalculator, meterRegistry);
    }

    @Test
    @DisplayName("Successfully park a vehicle")
    void testParkVehicle() {
        // Given
        Vehicle vehicle = new Vehicle("ABC123", VehicleType.CAR);

        // When
        Ticket ticket = parkingLotService.parkVehicle(vehicle);

        // Then
        assertThat(ticket).isNotNull();
        assertThat(ticket.ticketId()).isNotBlank();
        assertThat(ticket.vehicleLicensePlate()).isEqualTo("ABC123");
        assertThat(ticket.vehicleType()).isEqualTo(VehicleType.CAR);
        assertThat(ticket.spotId()).isNotBlank();
        assertThat(ticket.entryTime()).isNotNull();
    }

    @Test
    @DisplayName("Park multiple vehicles of different types")
    void testParkMultipleVehicles() {
        // Given
        Vehicle motorcycle = new Vehicle("BIKE001", VehicleType.MOTORCYCLE);
        Vehicle car = new Vehicle("CAR001", VehicleType.CAR);
        Vehicle truck = new Vehicle("TRUCK001", VehicleType.TRUCK);

        // When
        Ticket ticket1 = parkingLotService.parkVehicle(motorcycle);
        Ticket ticket2 = parkingLotService.parkVehicle(car);
        Ticket ticket3 = parkingLotService.parkVehicle(truck);

        // Then
        assertThat(ticket1.vehicleType()).isEqualTo(VehicleType.MOTORCYCLE);
        assertThat(ticket2.vehicleType()).isEqualTo(VehicleType.CAR);
        assertThat(ticket3.vehicleType()).isEqualTo(VehicleType.TRUCK);
        assertThat(parkingLotService.getActiveTicketCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Exit vehicle and calculate fee")
    void testExitVehicle() throws InterruptedException {
        // Given
        Vehicle vehicle = new Vehicle("ABC123", VehicleType.CAR);
        Ticket ticket = parkingLotService.parkVehicle(vehicle);

        // Wait a bit to ensure some parking duration
        Thread.sleep(100);

        // When
        double fee = parkingLotService.exitVehicle(ticket.ticketId());

        // Then
        assertThat(fee).isGreaterThan(0);
        assertThat(parkingLotService.getActiveTicketCount()).isZero();
    }

    @Test
    @DisplayName("Throw exception for invalid ticket")
    void testInvalidTicket() {
        assertThatThrownBy(() -> parkingLotService.exitVehicle("INVALID-TICKET"))
                .isInstanceOf(InvalidTicketException.class)
                .hasMessageContaining("Invalid ticket ID");
    }

    @Test
    @DisplayName("Get real-time availability")
    void testGetAvailability() {
        // When
        Map<VehicleType, Long> availability = parkingLotService.getAvailability();

        // Then
        assertThat(availability).containsKeys(VehicleType.MOTORCYCLE, VehicleType.CAR, VehicleType.TRUCK);
        assertThat(availability.get(VehicleType.MOTORCYCLE)).isGreaterThan(0);
        assertThat(availability.get(VehicleType.CAR)).isGreaterThan(0);
        assertThat(availability.get(VehicleType.TRUCK)).isGreaterThan(0);
    }

    @Test
    @DisplayName("Availability decreases when vehicle parks")
    void testAvailabilityDecreasesOnPark() {
        // Given
        Map<VehicleType, Long> initialAvailability = parkingLotService.getAvailability();
        long initialCarSpots = initialAvailability.get(VehicleType.CAR);

        // When
        Vehicle car = new Vehicle("CAR001", VehicleType.CAR);
        parkingLotService.parkVehicle(car);

        // Then
        Map<VehicleType, Long> newAvailability = parkingLotService.getAvailability();
        long newCarSpots = newAvailability.get(VehicleType.CAR);
        assertThat(newCarSpots).isEqualTo(initialCarSpots - 1);
    }

    @Test
    @DisplayName("Availability increases when vehicle exits")
    void testAvailabilityIncreasesOnExit() {
        // Given
        Vehicle car = new Vehicle("CAR001", VehicleType.CAR);
        Ticket ticket = parkingLotService.parkVehicle(car);

        Map<VehicleType, Long> availabilityAfterPark = parkingLotService.getAvailability();
        long spotsAfterPark = availabilityAfterPark.get(VehicleType.CAR);

        // When
        parkingLotService.exitVehicle(ticket.ticketId());

        // Then
        Map<VehicleType, Long> availabilityAfterExit = parkingLotService.getAvailability();
        long spotsAfterExit = availabilityAfterExit.get(VehicleType.CAR);
        assertThat(spotsAfterExit).isEqualTo(spotsAfterPark + 1);
    }

    @Test
    @DisplayName("Get ticket by ID")
    void testGetTicket() {
        // Given
        Vehicle vehicle = new Vehicle("ABC123", VehicleType.CAR);
        Ticket parkedTicket = parkingLotService.parkVehicle(vehicle);

        // When
        var retrievedTicket = parkingLotService.getTicket(parkedTicket.ticketId());

        // Then
        assertThat(retrievedTicket).isPresent();
        assertThat(retrievedTicket.get().ticketId()).isEqualTo(parkedTicket.ticketId());
    }

    @Test
    @DisplayName("Parking lot has multiple levels")
    void testParkingLotStructure() {
        assertThat(parkingLotService.getLevelCount()).isEqualTo(3);
        assertThat(parkingLotService.getName()).isEqualTo("Main Parking Lot");
    }
}
