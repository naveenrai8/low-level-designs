package com.learning.carrental.service;

import com.learning.carrental.model.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class CarRentalService {
    private final ConcurrentHashMap<String, Car> cars = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Reservation> reservations = new ConcurrentHashMap<>();

    public void addCar(Car car) {
        cars.put(car.getLicensePlate(), car);
    }

    public List<Car> getAvailableCars() {
        return cars.values().stream()
                .filter(car -> car.getStatus() == CarStatus.AVAILABLE)
                .collect(Collectors.toList());
    }

    public Reservation reserveCar(String licensePlate, String customerId, LocalDate startDate, LocalDate endDate) throws Exception {
        Car car = cars.get(licensePlate);
        if (car == null) {
            throw new Exception("Car not found");
        }

        if (!car.reserve()) {
            throw new Exception("Car not available for reservation");
        }

        long days = ChronoUnit.DAYS.between(startDate, endDate);
        if (days <= 0) {
            car.release();
            throw new Exception("Invalid date range");
        }

        double totalCost = days * car.getDailyRate();
        Reservation reservation = new Reservation(customerId, licensePlate, startDate, endDate, totalCost);
        reservations.put(reservation.getReservationId(), reservation);

        return reservation;
    }

    public void returnCar(String reservationId) throws Exception {
        Reservation reservation = reservations.get(reservationId);
        if (reservation == null) {
            throw new Exception("Reservation not found");
        }

        Car car = cars.get(reservation.getLicensePlate());
        if (car != null) {
            car.release();
        }

        reservation.complete();
    }

    public void cancelReservation(String reservationId) throws Exception {
        Reservation reservation = reservations.get(reservationId);
        if (reservation == null) {
            throw new Exception("Reservation not found");
        }

        if (reservation.getStatus() == ReservationStatus.COMPLETED) {
            throw new Exception("Cannot cancel completed reservation");
        }

        Car car = cars.get(reservation.getLicensePlate());
        if (car != null) {
            car.release();
        }

        reservation.cancel();
    }

    public Reservation getReservation(String reservationId) {
        return reservations.get(reservationId);
    }

    public Car getCar(String licensePlate) {
        return cars.get(licensePlate);
    }
}
