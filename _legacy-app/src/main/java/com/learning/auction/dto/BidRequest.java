package com.learning.auction.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record BidRequest(
    @NotBlank(message = "Bidder ID cannot be blank")
    String bidderId,

    @Min(value = 0, message = "Bid amount must be positive")
    double amount
) {}
