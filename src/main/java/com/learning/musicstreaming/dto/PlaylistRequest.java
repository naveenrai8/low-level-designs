package com.learning.musicstreaming.dto;

import jakarta.validation.constraints.NotBlank;

public record PlaylistRequest(
    @NotBlank(message = "User ID cannot be blank")
    String userId,

    @NotBlank(message = "Name cannot be blank")
    String name
) {}
