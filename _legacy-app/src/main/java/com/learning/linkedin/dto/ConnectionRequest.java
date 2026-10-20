package com.learning.linkedin.dto;

import jakarta.validation.constraints.NotBlank;

public record ConnectionRequest(
    @NotBlank(message = "User ID 1 cannot be blank")
    String userId1,

    @NotBlank(message = "User ID 2 cannot be blank")
    String userId2
) {}
