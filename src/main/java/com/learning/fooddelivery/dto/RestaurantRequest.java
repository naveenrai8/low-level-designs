package com.learning.fooddelivery.dto;

import jakarta.validation.constraints.NotBlank;

public record RestaurantRequest(
    @NotBlank(message = "Name cannot be blank")
    String name,

    @NotBlank(message = "Location cannot be blank")
    String location
) {}
