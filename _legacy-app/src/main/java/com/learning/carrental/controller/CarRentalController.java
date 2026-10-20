package com.learning.carrental.controller;

import com.learning.carrental.dto.CarRequest;
import com.learning.carrental.dto.ReservationRequest;
import com.learning.carrental.dto.ReservationResponse;
import com.learning.carrental.model.Car;
import com.learning.carrental.model.Reservation;
import com.learning.carrental.service.CarRentalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/carrental")
@Tag(name = "Car Rental", description = "Car rental operations")
public class CarRentalController {

    private final CarRentalService carRentalService;

    public CarRentalController(CarRentalService carRentalService) {
        this.carRentalService = carRentalService;
    }

    @PostMapping("/cars")
    @Operation(summary = "Add a car to the fleet")
    public ResponseEntity<String> addCar(@Valid @RequestBody CarRequest request) {
        Car car = new Car(request.licensePlate(), request.make(), request.model(),
                         request.year(), request.dailyRate());
        carRentalService.addCar(car);
        return ResponseEntity.status(HttpStatus.CREATED).body("Car added successfully");
    }

    @GetMapping("/cars/available")
    @Operation(summary = "Get all available cars")
    public ResponseEntity<List<Car>> getAvailableCars() {
        return ResponseEntity.ok(carRentalService.getAvailableCars());
    }

    @GetMapping("/cars/{licensePlate}")
    @Operation(summary = "Get car details")
    public ResponseEntity<Car> getCar(@PathVariable String licensePlate) {
        Car car = carRentalService.getCar(licensePlate);
        return car != null ? ResponseEntity.ok(car) : ResponseEntity.notFound().build();
    }

    @PostMapping("/reservations")
    @Operation(summary = "Reserve a car")
    public ResponseEntity<?> reserveCar(@Valid @RequestBody ReservationRequest request) {
        try {
            Reservation reservation = carRentalService.reserveCar(
                request.licensePlate(), request.customerId(),
                request.startDate(), request.endDate()
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(reservation));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/reservations/{reservationId}")
    @Operation(summary = "Get reservation details")
    public ResponseEntity<ReservationResponse> getReservation(@PathVariable String reservationId) {
        Reservation reservation = carRentalService.getReservation(reservationId);
        return reservation != null ? ResponseEntity.ok(toResponse(reservation))
                                    : ResponseEntity.notFound().build();
    }

    @PostMapping("/reservations/{reservationId}/return")
    @Operation(summary = "Return a car")
    public ResponseEntity<?> returnCar(@PathVariable String reservationId) {
        try {
            carRentalService.returnCar(reservationId);
            return ResponseEntity.ok("Car returned successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/reservations/{reservationId}/cancel")
    @Operation(summary = "Cancel a reservation")
    public ResponseEntity<?> cancelReservation(@PathVariable String reservationId) {
        try {
            carRentalService.cancelReservation(reservationId);
            return ResponseEntity.ok("Reservation cancelled successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    private ReservationResponse toResponse(Reservation reservation) {
        return new ReservationResponse(
            reservation.getReservationId(),
            reservation.getCustomerId(),
            reservation.getLicensePlate(),
            reservation.getStartDate(),
            reservation.getEndDate(),
            reservation.getTotalCost(),
            reservation.getStatus()
        );
    }
}
