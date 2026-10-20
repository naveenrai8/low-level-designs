package com.learning.airline.controller;

import com.learning.airline.dto.BookingRequest;
import com.learning.airline.dto.BookingResponse;
import com.learning.airline.dto.FlightRequest;
import com.learning.airline.model.Flight;
import com.learning.airline.model.FlightBooking;
import com.learning.airline.service.AirlineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/airline")
@Tag(name = "Airline", description = "Airline management operations")
public class AirlineController {

    private final AirlineService airlineService;

    public AirlineController(AirlineService airlineService) {
        this.airlineService = airlineService;
    }

    @PostMapping("/flights")
    @Operation(summary = "Add a flight")
    public ResponseEntity<String> addFlight(@Valid @RequestBody FlightRequest request) {
        Flight flight = new Flight(
            request.flightNumber(), request.source(), request.destination(),
            request.departureTime(), request.capacity(), request.price()
        );
        airlineService.addFlight(flight);
        return ResponseEntity.status(HttpStatus.CREATED).body("Flight added successfully");
    }

    @GetMapping("/flights")
    @Operation(summary = "Get all flights")
    public ResponseEntity<List<Flight>> getAllFlights() {
        return ResponseEntity.ok(airlineService.getAllFlights());
    }

    @GetMapping("/flights/search")
    @Operation(summary = "Search flights")
    public ResponseEntity<List<Flight>> searchFlights(
            @RequestParam String source,
            @RequestParam String destination) {
        return ResponseEntity.ok(airlineService.searchFlights(source, destination));
    }

    @GetMapping("/flights/{flightNumber}")
    @Operation(summary = "Get flight details")
    public ResponseEntity<Flight> getFlight(@PathVariable String flightNumber) {
        Flight flight = airlineService.getFlight(flightNumber);
        return flight != null ? ResponseEntity.ok(flight) : ResponseEntity.notFound().build();
    }

    @PostMapping("/bookings")
    @Operation(summary = "Book a flight")
    public ResponseEntity<?> bookFlight(@Valid @RequestBody BookingRequest request) {
        try {
            FlightBooking booking = airlineService.bookFlight(
                request.flightNumber(), request.passengerName(), request.numberOfSeats()
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(booking));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/bookings/{bookingId}")
    @Operation(summary = "Get booking details")
    public ResponseEntity<BookingResponse> getBooking(@PathVariable String bookingId) {
        FlightBooking booking = airlineService.getBooking(bookingId);
        return booking != null ? ResponseEntity.ok(toResponse(booking))
                                : ResponseEntity.notFound().build();
    }

    private BookingResponse toResponse(FlightBooking booking) {
        return new BookingResponse(
            booking.getBookingId(),
            booking.getFlightNumber(),
            booking.getPassengerName(),
            booking.getNumberOfSeats(),
            booking.getTotalCost(),
            booking.getStatus()
        );
    }
}
