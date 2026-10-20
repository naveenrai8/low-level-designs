package com.learning.wallet.dto;

import jakarta.validation.constraints.NotBlank;

public record WalletRequest(
    @NotBlank(message = "User ID cannot be blank")
    String userId
) {}
