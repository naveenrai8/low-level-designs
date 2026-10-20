package com.learning.stockbroker.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record StockRequest(
    @NotBlank(message = "Symbol cannot be blank")
    String symbol,

    @NotBlank(message = "Name cannot be blank")
    String name,

    @Min(value = 0, message = "Price must be positive")
    double initialPrice
) {}
