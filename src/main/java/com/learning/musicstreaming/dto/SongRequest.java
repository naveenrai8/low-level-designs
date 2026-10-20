package com.learning.musicstreaming.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record SongRequest(
    @NotBlank(message = "Title cannot be blank")
    String title,

    @NotBlank(message = "Artist cannot be blank")
    String artist,

    @NotBlank(message = "Album cannot be blank")
    String album,

    @Min(value = 1, message = "Duration must be at least 1 second")
    int durationSeconds
) {}
