package com.learning.wallet.service;

import com.learning.wallet.model.Transaction;
import com.learning.wallet.model.TransactionType;
import com.learning.wallet.model.Wallet;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WalletService {
    private final ConcurrentHashMap<String, Wallet> wallets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Transaction>> transactions = new ConcurrentHashMap<>();

    public Wallet createWallet(String userId) {
        String walletId = UUID.randomUUID().toString();
        Wallet wallet = new Wallet(walletId, userId);
        wallets.put(walletId, wallet);
        transactions.put(walletId, new ArrayList<>());
        return wallet;
    }

    public Wallet getWallet(String walletId) {
        return wallets.get(walletId);
    }

    public Transaction addMoney(String walletId, double amount, String description) throws Exception {
        Wallet wallet = wallets.get(walletId);
        if (wallet == null) {
            throw new Exception("Wallet not found");
        }

        wallet.credit(amount);
        Transaction transaction = new Transaction(walletId, TransactionType.CREDIT, amount, description);
        transactions.get(walletId).add(transaction);
        return transaction;
    }

    public Transaction withdrawMoney(String walletId, double amount, String description) throws Exception {
        Wallet wallet = wallets.get(walletId);
        if (wallet == null) {
            throw new Exception("Wallet not found");
        }

        if (!wallet.debit(amount)) {
            throw new Exception("Insufficient balance");
        }

        Transaction transaction = new Transaction(walletId, TransactionType.DEBIT, amount, description);
        transactions.get(walletId).add(transaction);
        return transaction;
    }

    public Transaction transfer(String fromWalletId, String toWalletId, double amount, String description) throws Exception {
        Wallet fromWallet = wallets.get(fromWalletId);
        Wallet toWallet = wallets.get(toWalletId);

        if (fromWallet == null || toWallet == null) {
            throw new Exception("Wallet not found");
        }

        if (!fromWallet.debit(amount)) {
            throw new Exception("Insufficient balance");
        }

        toWallet.credit(amount);

        Transaction debitTxn = new Transaction(fromWalletId, TransactionType.TRANSFER, amount,
                                              "Transfer to " + toWalletId + ": " + description);
        Transaction creditTxn = new Transaction(toWalletId, TransactionType.TRANSFER, amount,
                                               "Transfer from " + fromWalletId + ": " + description);

        transactions.get(fromWalletId).add(debitTxn);
        transactions.get(toWalletId).add(creditTxn);

        return debitTxn;
    }

    public List<Transaction> getTransactionHistory(String walletId) {
        return new ArrayList<>(transactions.getOrDefault(walletId, new ArrayList<>()));
    }
}
