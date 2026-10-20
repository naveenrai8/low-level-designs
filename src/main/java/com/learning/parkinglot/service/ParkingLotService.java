package com.learning.parkinglot.service;

import com.learning.parkinglot.exception.InvalidTicketException;
import com.learning.parkinglot.exception.NoSpotAvailableException;
import com.learning.parkinglot.model.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Main service for parking lot operations.
 * Thread-safe implementation using concurrent data structures.
 *
 * Design Pattern: Facade - provides a simplified interface to the parking lot subsystem
 */
@Service
public class ParkingLotService {
    private static final Logger logger = LoggerFactory.getLogger(ParkingLotService.class);

    private final String name;
    private final List<ParkingLevel> levels;
    private final ConcurrentHashMap<String, Ticket> activeTickets;
    private final ConcurrentHashMap<String, ParkingSpot> spotRegistry;
    private final FeeCalculator feeCalculator;
    private final AtomicLong ticketCounter;
    private final MeterRegistry meterRegistry;

    public ParkingLotService(FeeCalculator feeCalculator, MeterRegistry meterRegistry) {
        this.name = "Main Parking Lot";
        this.levels = new ArrayList<>();
        this.activeTickets = new ConcurrentHashMap<>();
        this.spotRegistry = new ConcurrentHashMap<>();
        this.feeCalculator = feeCalculator;
        this.ticketCounter = new AtomicLong(0);
        this.meterRegistry = meterRegistry;

        // Initialize parking lot structure
        initializeParkingLot();

        logger.info("ParkingLotService initialized with {} levels", levels.size());
    }

    /**
     * Initializes the parking lot with multiple levels and spots.
     * Configuration: 3 levels with mixed vehicle type spots.
     */
    private void initializeParkingLot() {
        // Level 1: 10 Motorcycle, 20 Car, 5 Truck
        ParkingLevel level1 = new ParkingLevel(1);
        addSpotsToLevel(level1, VehicleType.MOTORCYCLE, 10);
        addSpotsToLevel(level1, VehicleType.CAR, 20);
        addSpotsToLevel(level1, VehicleType.TRUCK, 5);
        levels.add(level1);

        // Level 2: 15 Motorcycle, 30 Car, 8 Truck
        ParkingLevel level2 = new ParkingLevel(2);
        addSpotsToLevel(level2, VehicleType.MOTORCYCLE, 15);
        addSpotsToLevel(level2, VehicleType.CAR, 30);
        addSpotsToLevel(level2, VehicleType.TRUCK, 8);
        levels.add(level2);

        // Level 3: 10 Motorcycle, 25 Car, 10 Truck
        ParkingLevel level3 = new ParkingLevel(3);
        addSpotsToLevel(level3, VehicleType.MOTORCYCLE, 10);
        addSpotsToLevel(level3, VehicleType.CAR, 25);
        addSpotsToLevel(level3, VehicleType.TRUCK, 10);
        levels.add(level3);

        logger.info("Parking lot initialized: {} total levels, {} total spots",
                levels.size(), spotRegistry.size());
    }

    /**
     * Helper method to add spots to a level.
     */
    private void addSpotsToLevel(ParkingLevel level, VehicleType type, int count) {
        for (int i = 0; i < count; i++) {
            String spotId = String.format("L%d-%s-%d", level.getLevelNumber(), type, i + 1);
            ParkingSpot spot = new ParkingSpot(spotId, type, level.getLevelNumber());
            level.addSpot(spot);
            spotRegistry.put(spotId, spot);
        }
    }

    /**
     * Parks a vehicle in the parking lot.
     *
     * Time complexity: O(n) where n is the number of spots (worst case)
     * Space complexity: O(1)
     *
     * @param vehicle the vehicle to park
     * @return the generated ticket
     * @throws NoSpotAvailableException if no suitable spot is available
     */
    public Ticket parkVehicle(Vehicle vehicle) {
        String correlationId = UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);

        try {
            logger.info("Attempting to park vehicle: {}, type: {}", vehicle.licensePlate(), vehicle.type());

            // Find available spot across all levels
            ParkingSpot availableSpot = findAvailableSpot(vehicle.type())
                    .orElseThrow(() -> new NoSpotAvailableException(
                            "No available spots for vehicle type: " + vehicle.type()));

            // Occupy the spot
            availableSpot.occupy(vehicle);

            // Generate ticket
            String ticketId = "TKT-" + ticketCounter.incrementAndGet();
            Instant entryTime = Instant.now();
            Ticket ticket = Ticket.forEntry(ticketId, vehicle, availableSpot.getSpotId(), entryTime);

            // Store ticket
            activeTickets.put(ticketId, ticket);

            // Update metrics
            meterRegistry.counter("parking.vehicle.entry",
                    "vehicle_type", vehicle.type().toString()).increment();

            logger.info("Vehicle parked successfully. Ticket: {}, Spot: {}",
                    ticketId, availableSpot.getSpotId());

            return ticket;

        } finally {
            MDC.remove("correlationId");
        }
    }

    /**
     * Processes vehicle exit and calculates fee.
     *
     * Time complexity: O(1)
     * Space complexity: O(1)
     *
     * @param ticketId the ticket ID
     * @return the calculated fee
     * @throws InvalidTicketException if ticket is not found
     */
    public double exitVehicle(String ticketId) {
        String correlationId = UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);

        try {
            logger.info("Processing exit for ticket: {}", ticketId);

            Ticket ticket = activeTickets.get(ticketId);
            if (ticket == null) {
                throw new InvalidTicketException("Invalid ticket ID: " + ticketId);
            }

            Instant exitTime = Instant.now();

            // Calculate fee
            double fee = feeCalculator.calculateFee(ticket, exitTime);

            // Release spot
            ParkingSpot spot = spotRegistry.get(ticket.spotId());
            if (spot != null) {
                spot.release();
            }

            // Remove ticket from active tickets
            activeTickets.remove(ticketId);

            // Update metrics
            meterRegistry.counter("parking.vehicle.exit",
                    "vehicle_type", ticket.vehicleType().toString()).increment();

            logger.info("Vehicle exited successfully. Ticket: {}, Fee: ${}", ticketId, fee);

            return fee;

        } finally {
            MDC.remove("correlationId");
        }
    }

    /**
     * Gets real-time availability of parking spots.
     *
     * Time complexity: O(n) where n is the number of spots
     * Space complexity: O(1)
     *
     * @return map of vehicle type to available count
     */
    public Map<VehicleType, Long> getAvailability() {
        Map<VehicleType, Long> availability = levels.stream()
                .flatMap(level -> level.getAvailableCount().entrySet().stream())
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey,
                        Collectors.summingLong(Map.Entry::getValue)
                ));

        // Ensure all vehicle types are present in the map
        for (VehicleType type : VehicleType.values()) {
            availability.putIfAbsent(type, 0L);
        }

        logger.debug("Current availability: {}", availability);
        return availability;
    }

    /**
     * Gets a ticket by ID.
     *
     * @param ticketId the ticket ID
     * @return Optional containing the ticket if found
     */
    public Optional<Ticket> getTicket(String ticketId) {
        return Optional.ofNullable(activeTickets.get(ticketId));
    }

    /**
     * Finds an available spot for a vehicle type across all levels.
     *
     * @param vehicleType the vehicle type
     * @return Optional containing the spot if available
     */
    private Optional<ParkingSpot> findAvailableSpot(VehicleType vehicleType) {
        return levels.stream()
                .map(level -> level.findAvailableSpot(vehicleType))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    /**
     * Gets the count of active tickets (vehicles currently parked).
     *
     * @return count of active tickets
     */
    public int getActiveTicketCount() {
        return activeTickets.size();
    }

    /**
     * Gets parking lot information.
     *
     * @return parking lot name
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the number of levels.
     *
     * @return number of levels
     */
    public int getLevelCount() {
        return levels.size();
    }
}
