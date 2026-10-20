package com.learning.airline.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record FlightRequest(
    @NotBlank(message = "Flight number cannot be blank")
    String flightNumber,

    @NotBlank(message = "Source cannot be blank")
    String source,

    @NotBlank(message = "Destination cannot be blank")
    String destination,

    @NotNull(message = "Departure time cannot be null")
    LocalDateTime departureTime,

    @Min(value = 1, message = "Capacity must be at least 1")
    int capacity,

    @Min(value = 0, message = "Price must be positive")
    double price
) {}
