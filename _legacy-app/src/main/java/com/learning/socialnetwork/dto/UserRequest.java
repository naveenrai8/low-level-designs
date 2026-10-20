package com.learning.socialnetwork.dto;

import jakarta.validation.constraints.NotBlank;

public record UserRequest(
    @NotBlank(message = "Name cannot be blank")
    String name
) {}
