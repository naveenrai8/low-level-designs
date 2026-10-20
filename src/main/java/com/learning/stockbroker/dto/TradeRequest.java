package com.learning.stockbroker.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record TradeRequest(
    @NotBlank(message = "Symbol cannot be blank")
    String symbol,

    @Min(value = 1, message = "Quantity must be at least 1")
    int quantity
) {}
