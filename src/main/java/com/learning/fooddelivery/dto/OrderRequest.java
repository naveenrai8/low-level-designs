package com.learning.fooddelivery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record OrderRequest(
    @NotBlank(message = "Restaurant ID cannot be blank")
    String restaurantId,

    @NotBlank(message = "Customer ID cannot be blank")
    String customerId,

    @NotEmpty(message = "Item IDs cannot be empty")
    List<String> itemIds
) {}
