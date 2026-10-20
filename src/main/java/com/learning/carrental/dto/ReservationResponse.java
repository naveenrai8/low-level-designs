package com.learning.carrental.dto;

import com.learning.carrental.model.ReservationStatus;
import java.time.LocalDate;

public record ReservationResponse(
    String reservationId,
    String customerId,
    String licensePlate,
    LocalDate startDate,
    LocalDate endDate,
    double totalCost,
    ReservationStatus status
) {}
