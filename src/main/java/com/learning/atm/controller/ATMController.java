package com.learning.atm.controller;

import com.learning.atm.model.Transaction;
import com.learning.atm.service.ATMService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/atm")
public class ATMController {
    private final ATMService atmService;

    public ATMController(ATMService atmService) {
        this.atmService = atmService;
    }

    @PostMapping("/balance")
    public ResponseEntity<?> checkBalance(
            @RequestParam String cardNumber,
            @RequestParam String pin) {
        try {
            double balance = atmService.checkBalance(cardNumber, pin);
            return ResponseEntity.ok(Map.of("balance", balance));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/withdraw")
    public ResponseEntity<?> withdraw(
            @RequestParam String cardNumber,
            @RequestParam String pin,
            @RequestParam double amount) {
        try {
            Transaction txn = atmService.withdrawCash(cardNumber, pin, amount);
            return ResponseEntity.ok(txn);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/deposit")
    public ResponseEntity<?> deposit(
            @RequestParam String cardNumber,
            @RequestParam String pin,
            @RequestParam double amount) {
        try {
            Transaction txn = atmService.depositCash(cardNumber, pin, amount);
            return ResponseEntity.ok(txn);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/history")
    public ResponseEntity<?> getHistory(
            @RequestParam String cardNumber,
            @RequestParam String pin) {
        try {
            List<Transaction> history = atmService.getTransactionHistory(cardNumber, pin);
            return ResponseEntity.ok(history);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
