package com.learning.splitwise.model;

import java.time.Instant;
import java.util.Map;

public class Expense {
    private final String expenseId;
    private final String groupId;
    private final String description;
    private final double amount;
    private final String paidBy;
    private final Map<String, Double> splits;
    private final Instant createdAt;

    public Expense(String expenseId, String groupId, String description,
                  double amount, String paidBy, Map<String, Double> splits) {
        this.expenseId = expenseId;
        this.groupId = groupId;
        this.description = description;
        this.amount = amount;
        this.paidBy = paidBy;
        this.splits = splits;
        this.createdAt = Instant.now();
    }

    public String getExpenseId() { return expenseId; }
    public double getAmount() { return amount; }
    public Map<String, Double> getSplits() { return splits; }
}
