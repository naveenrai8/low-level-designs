package com.learning.linkedin.dto;

import jakarta.validation.constraints.NotBlank;

public record ProfileRequest(
    @NotBlank(message = "User ID cannot be blank")
    String userId
) {}
