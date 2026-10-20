package com.learning.wallet.controller;

import com.learning.wallet.dto.MoneyRequest;
import com.learning.wallet.dto.TransferRequest;
import com.learning.wallet.dto.WalletRequest;
import com.learning.wallet.model.Transaction;
import com.learning.wallet.model.Wallet;
import com.learning.wallet.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/wallet")
@Tag(name = "Wallet", description = "Digital wallet operations")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping("/wallets")
    @Operation(summary = "Create a new wallet")
    public ResponseEntity<Wallet> createWallet(@Valid @RequestBody WalletRequest request) {
        Wallet wallet = walletService.createWallet(request.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(wallet);
    }

    @GetMapping("/wallets/{walletId}")
    @Operation(summary = "Get wallet details")
    public ResponseEntity<Wallet> getWallet(@PathVariable String walletId) {
        Wallet wallet = walletService.getWallet(walletId);
        return wallet != null ? ResponseEntity.ok(wallet) : ResponseEntity.notFound().build();
    }

    @PostMapping("/wallets/{walletId}/credit")
    @Operation(summary = "Add money to wallet")
    public ResponseEntity<?> addMoney(
            @PathVariable String walletId,
            @Valid @RequestBody MoneyRequest request) {
        try {
            Transaction transaction = walletService.addMoney(walletId, request.amount(), request.description());
            return ResponseEntity.ok(transaction);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/wallets/{walletId}/debit")
    @Operation(summary = "Withdraw money from wallet")
    public ResponseEntity<?> withdrawMoney(
            @PathVariable String walletId,
            @Valid @RequestBody MoneyRequest request) {
        try {
            Transaction transaction = walletService.withdrawMoney(walletId, request.amount(), request.description());
            return ResponseEntity.ok(transaction);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/wallets/{fromWalletId}/transfer")
    @Operation(summary = "Transfer money between wallets")
    public ResponseEntity<?> transfer(
            @PathVariable String fromWalletId,
            @Valid @RequestBody TransferRequest request) {
        try {
            Transaction transaction = walletService.transfer(
                fromWalletId, request.toWalletId(), request.amount(), request.description()
            );
            return ResponseEntity.ok(transaction);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/wallets/{walletId}/transactions")
    @Operation(summary = "Get transaction history")
    public ResponseEntity<List<Transaction>> getTransactionHistory(@PathVariable String walletId) {
        return ResponseEntity.ok(walletService.getTransactionHistory(walletId));
    }
}
