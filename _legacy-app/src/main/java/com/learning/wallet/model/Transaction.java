package com.learning.wallet.model;

import java.time.Instant;
import java.util.UUID;

public class Transaction {
    private final String transactionId;
    private final String walletId;
    private final TransactionType type;
    private final double amount;
    private final Instant timestamp;
    private final String description;

    public Transaction(String walletId, TransactionType type, double amount, String description) {
        this.transactionId = UUID.randomUUID().toString();
        this.walletId = walletId;
        this.type = type;
        this.amount = amount;
        this.timestamp = Instant.now();
        this.description = description;
    }

    public String getTransactionId() { return transactionId; }
    public String getWalletId() { return walletId; }
    public TransactionType getType() { return type; }
    public double getAmount() { return amount; }
    public Instant getTimestamp() { return timestamp; }
    public String getDescription() { return description; }
}
