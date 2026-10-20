package com.learning.carrental.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CarRequest(
    @NotBlank(message = "License plate cannot be blank")
    String licensePlate,

    @NotBlank(message = "Make cannot be blank")
    String make,

    @NotBlank(message = "Model cannot be blank")
    String model,

    @Min(value = 1900, message = "Year must be valid")
    int year,

    @Min(value = 0, message = "Daily rate must be positive")
    double dailyRate
) {}
