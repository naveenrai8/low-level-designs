package com.learning.wallet.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record TransferRequest(
    @NotBlank(message = "To wallet ID cannot be blank")
    String toWalletId,

    @Min(value = 0, message = "Amount must be positive")
    double amount,

    @NotBlank(message = "Description cannot be blank")
    String description
) {}
