package com.learning.restaurant.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record TableOrderRequest(
    @Min(value = 1, message = "Table number must be positive")
    int tableNumber,

    @NotEmpty(message = "Item IDs cannot be empty")
    List<String> itemIds
) {}
