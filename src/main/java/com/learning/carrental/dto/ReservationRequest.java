package com.learning.carrental.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record ReservationRequest(
    @NotBlank(message = "License plate cannot be blank")
    String licensePlate,

    @NotBlank(message = "Customer ID cannot be blank")
    String customerId,

    @NotNull(message = "Start date cannot be null")
    LocalDate startDate,

    @NotNull(message = "End date cannot be null")
    LocalDate endDate
) {}
