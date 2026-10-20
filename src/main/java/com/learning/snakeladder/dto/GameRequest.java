package com.learning.snakeladder.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record GameRequest(
    @NotEmpty(message = "Players list cannot be empty")
    List<String> players
) {}
