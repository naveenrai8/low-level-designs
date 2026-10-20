package com.learning.airline.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record BookingRequest(
    @NotBlank(message = "Flight number cannot be blank")
    String flightNumber,

    @NotBlank(message = "Passenger name cannot be blank")
    String passengerName,

    @Min(value = 1, message = "Number of seats must be at least 1")
    int numberOfSeats
) {}
