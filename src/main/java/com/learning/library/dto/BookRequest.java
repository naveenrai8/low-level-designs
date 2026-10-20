package com.learning.library.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record BookRequest(
    @NotBlank(message = "ISBN cannot be blank")
    String isbn,

    @NotBlank(message = "Title cannot be blank")
    String title,

    @NotBlank(message = "Author cannot be blank")
    String author,

    @Min(value = 1000, message = "Publication year must be valid")
    int publicationYear
) {}
