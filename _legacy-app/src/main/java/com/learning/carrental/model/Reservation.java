package com.learning.carrental.model;

import java.time.LocalDate;
import java.util.UUID;

public class Reservation {
    private final String reservationId;
    private final String customerId;
    private final String licensePlate;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final double totalCost;
    private volatile ReservationStatus status;

    public Reservation(String customerId, String licensePlate, LocalDate startDate, LocalDate endDate, double totalCost) {
        this.reservationId = UUID.randomUUID().toString();
        this.customerId = customerId;
        this.licensePlate = licensePlate;
        this.startDate = startDate;
        this.endDate = endDate;
        this.totalCost = totalCost;
        this.status = ReservationStatus.CONFIRMED;
    }

    public synchronized void complete() {
        status = ReservationStatus.COMPLETED;
    }

    public synchronized void cancel() {
        status = ReservationStatus.CANCELLED;
    }

    public String getReservationId() { return reservationId; }
    public String getCustomerId() { return customerId; }
    public String getLicensePlate() { return licensePlate; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public double getTotalCost() { return totalCost; }
    public ReservationStatus getStatus() { return status; }
}
