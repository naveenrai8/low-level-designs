package com.learning.splitwise.service;

import com.learning.splitwise.model.Expense;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class SplitwiseService {
    private final ConcurrentHashMap<String, Expense> expenses = new ConcurrentHashMap<>();

    public Expense createExpense(String groupId, String description, double amount, String paidBy, List<String> participants) {
        String expenseId = UUID.randomUUID().toString();

        // Calculate equal splits
        double splitAmount = amount / participants.size();
        java.util.Map<String, Double> splits = new java.util.HashMap<>();
        participants.forEach(p -> splits.put(p, splitAmount));

        Expense expense = new Expense(expenseId, groupId, description, amount, paidBy, splits);
        expenses.put(expenseId, expense);
        return expense;
    }

    public List<Expense> getUserExpenses(String userId) {
        return expenses.values().stream()
                .filter(expense -> expense.getSplits().containsKey(userId))
                .collect(Collectors.toList());
    }

    public Expense getExpense(String expenseId) {
        return expenses.get(expenseId);
    }

    public List<Expense> getAllExpenses() {
        return List.copyOf(expenses.values());
    }
}
