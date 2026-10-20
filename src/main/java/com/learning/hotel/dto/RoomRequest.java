package com.learning.hotel.dto;

import com.learning.hotel.model.RoomType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RoomRequest(
    @NotBlank(message = "Room number cannot be blank")
    String roomNumber,

    @NotNull(message = "Room type cannot be null")
    RoomType type,

    @Min(value = 0, message = "Price must be positive")
    double pricePerNight
) {}
