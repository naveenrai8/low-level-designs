package com.learning.hotel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record BookingRequest(
    @NotBlank(message = "Room number cannot be blank")
    String roomNumber,

    @NotBlank(message = "Guest name cannot be blank")
    String guestName,

    @NotNull(message = "Check-in date cannot be null")
    LocalDate checkInDate,

    @NotNull(message = "Check-out date cannot be null")
    LocalDate checkOutDate
) {}
