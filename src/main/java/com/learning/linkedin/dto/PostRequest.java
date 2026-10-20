package com.learning.linkedin.dto;

import jakarta.validation.constraints.NotBlank;

public record PostRequest(
    @NotBlank(message = "User ID cannot be blank")
    String userId,

    @NotBlank(message = "Content cannot be blank")
    String content
) {}
