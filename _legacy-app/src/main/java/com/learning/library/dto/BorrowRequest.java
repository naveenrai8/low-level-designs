package com.learning.library.dto;

import jakarta.validation.constraints.NotBlank;

public record BorrowRequest(
    @NotBlank(message = "ISBN cannot be blank")
    String isbn,

    @NotBlank(message = "Member ID cannot be blank")
    String memberId
) {}
