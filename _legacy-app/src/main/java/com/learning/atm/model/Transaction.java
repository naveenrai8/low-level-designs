package com.learning.atm.model;

import java.time.Instant;

public record Transaction(
    String transactionId,
    String accountNumber,
    TransactionType type,
    double amount,
    Instant timestamp,
    double balanceAfter
) {
    public static Transaction create(String accountNumber, TransactionType type,
                                    double amount, double balanceAfter) {
        return new Transaction(
            java.util.UUID.randomUUID().toString(),
            accountNumber,
            type,
            amount,
            Instant.now(),
            balanceAfter
        );
    }
}
