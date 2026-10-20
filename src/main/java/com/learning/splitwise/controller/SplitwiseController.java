package com.learning.splitwise.controller;

import com.learning.splitwise.model.Expense;
import com.learning.splitwise.service.SplitwiseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/splitwise")
@Tag(name = "Splitwise", description = "Expense splitting operations")
public class SplitwiseController {

    private final SplitwiseService splitwiseService;

    public SplitwiseController(SplitwiseService splitwiseService) {
        this.splitwiseService = splitwiseService;
    }

    @PostMapping("/expenses")
    @Operation(summary = "Create an expense")
    public ResponseEntity<Expense> createExpense(
            @RequestParam String groupId,
            @RequestParam String description,
            @RequestParam double amount,
            @RequestParam String paidBy,
            @RequestParam List<String> participants) {
        Expense expense = splitwiseService.createExpense(groupId, description, amount, paidBy, participants);
        return ResponseEntity.status(HttpStatus.CREATED).body(expense);
    }

    @GetMapping("/expenses")
    @Operation(summary = "Get all expenses")
    public ResponseEntity<List<Expense>> getAllExpenses() {
        return ResponseEntity.ok(splitwiseService.getAllExpenses());
    }

    @GetMapping("/expenses/{expenseId}")
    @Operation(summary = "Get expense details")
    public ResponseEntity<Expense> getExpense(@PathVariable String expenseId) {
        Expense expense = splitwiseService.getExpense(expenseId);
        return expense != null ? ResponseEntity.ok(expense) : ResponseEntity.notFound().build();
    }

    @GetMapping("/users/{userId}/expenses")
    @Operation(summary = "Get user expenses")
    public ResponseEntity<List<Expense>> getUserExpenses(@PathVariable String userId) {
        return ResponseEntity.ok(splitwiseService.getUserExpenses(userId));
    }
}
