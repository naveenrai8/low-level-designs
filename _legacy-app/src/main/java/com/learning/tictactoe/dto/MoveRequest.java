package com.learning.tictactoe.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record MoveRequest(
    @Min(value = 0, message = "Row must be between 0 and 2")
    @Max(value = 2, message = "Row must be between 0 and 2")
    int row,

    @Min(value = 0, message = "Column must be between 0 and 2")
    @Max(value = 2, message = "Column must be between 0 and 2")
    int col
) {}
