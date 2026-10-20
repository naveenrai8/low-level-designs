package com.learning.auction.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record AuctionRequest(
    @NotBlank(message = "Item name cannot be blank")
    String itemName,

    @Min(value = 0, message = "Starting price must be positive")
    double startingPrice,

    @Min(value = 1, message = "Duration must be at least 1 minute")
    long durationMinutes
) {}
