package com.learning.restaurant.dto;

import com.learning.restaurant.model.MenuCategory;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record MenuItemRequest(
    @NotBlank(message = "Name cannot be blank")
    String name,

    @NotNull(message = "Category cannot be null")
    MenuCategory category,

    @Min(value = 0, message = "Price must be positive")
    double price
) {}
