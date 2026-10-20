package com.learning.parkinglot.controller;

import com.learning.parkinglot.dto.AvailabilityResponse;
import com.learning.parkinglot.dto.ExitResponse;
import com.learning.parkinglot.dto.VehicleRequest;
import com.learning.parkinglot.model.Ticket;
import com.learning.parkinglot.model.Vehicle;
import com.learning.parkinglot.service.ParkingLotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * REST Controller for Parking Lot operations.
 * Provides endpoints for vehicle entry, exit, and availability queries.
 */
@RestController
@RequestMapping("/api/parking")
@Tag(name = "Parking Lot", description = "Parking Lot Management APIs")
public class ParkingLotController {
    private static final Logger logger = LoggerFactory.getLogger(ParkingLotController.class);

    private final ParkingLotService parkingLotService;

    public ParkingLotController(ParkingLotService parkingLotService) {
        this.parkingLotService = parkingLotService;
    }

    /**
     * Parks a vehicle in the parking lot.
     *
     * @param request the vehicle information
     * @return the generated ticket
     */
    @PostMapping("/entry")
    @Operation(summary = "Park a vehicle", description = "Parks a vehicle and generates a ticket")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Vehicle parked successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "503", description = "No spots available")
    })
    public ResponseEntity<Ticket> parkVehicle(@Valid @RequestBody VehicleRequest request) {
        logger.info("Received parking request for vehicle: {}", request.licensePlate());

        Vehicle vehicle = new Vehicle(request.licensePlate(), request.type());
        Ticket ticket = parkingLotService.parkVehicle(vehicle);

        return ResponseEntity.status(HttpStatus.CREATED).body(ticket);
    }

    /**
     * Processes vehicle exit and calculates fee.
     *
     * @param ticketId the ticket ID
     * @return exit response with fee information
     */
    @PostMapping("/exit/{ticketId}")
    @Operation(summary = "Exit vehicle", description = "Processes vehicle exit and calculates fee")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Exit processed successfully"),
            @ApiResponse(responseCode = "404", description = "Ticket not found")
    })
    public ResponseEntity<ExitResponse> exitVehicle(@PathVariable String ticketId) {
        logger.info("Received exit request for ticket: {}", ticketId);

        double fee = parkingLotService.exitVehicle(ticketId);
        ExitResponse response = ExitResponse.of(ticketId, fee, Instant.now());

        return ResponseEntity.ok(response);
    }

    /**
     * Gets real-time availability of parking spots.
     *
     * @return availability information
     */
    @GetMapping("/availability")
    @Operation(summary = "Check availability", description = "Gets real-time parking spot availability")
    @ApiResponse(responseCode = "200", description = "Availability retrieved successfully")
    public ResponseEntity<AvailabilityResponse> getAvailability() {
        logger.debug("Received availability request");

        var availability = parkingLotService.getAvailability();
        AvailabilityResponse response = AvailabilityResponse.of(availability);

        return ResponseEntity.ok(response);
    }

    /**
     * Gets ticket information by ID.
     *
     * @param ticketId the ticket ID
     * @return ticket information
     */
    @GetMapping("/ticket/{ticketId}")
    @Operation(summary = "Get ticket", description = "Retrieves ticket information by ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Ticket found"),
            @ApiResponse(responseCode = "404", description = "Ticket not found")
    })
    public ResponseEntity<Ticket> getTicket(@PathVariable String ticketId) {
        logger.debug("Received ticket query for: {}", ticketId);

        return parkingLotService.getTicket(ticketId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Health check endpoint for parking lot service.
     *
     * @return health status
     */
    @GetMapping("/health")
    @Operation(summary = "Health check", description = "Checks if parking lot service is operational")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Parking Lot Service is operational");
    }
}
