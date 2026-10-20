package com.learning.atm.service;

import com.learning.atm.model.Account;
import com.learning.atm.model.Transaction;
import com.learning.atm.model.TransactionType;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ATMService {
    private final ConcurrentHashMap<String, Account> accounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Transaction>> transactionHistory = new ConcurrentHashMap<>();
    private volatile double atmCashBalance = 50000.0; // ATM has $50,000 initially

    public ATMService() {
        initializeAccounts();
    }

    private void initializeAccounts() {
        accounts.put("1234-5678-9012-3456", new Account("ACC001", "1234-5678-9012-3456", "1234", 5000.0));
        accounts.put("9876-5432-1098-7654", new Account("ACC002", "9876-5432-1098-7654", "5678", 10000.0));
    }

    public Account authenticateCard(String cardNumber, String pin) throws Exception {
        Account account = accounts.get(cardNumber);
        if (account == null) {
            throw new Exception("Invalid card number");
        }
        if (!account.validatePin(pin)) {
            throw new Exception("Invalid PIN");
        }
        return account;
    }

    public double checkBalance(String cardNumber, String pin) throws Exception {
        Account account = authenticateCard(cardNumber, pin);
        Transaction txn = Transaction.create(
            account.getAccountNumber(),
            TransactionType.BALANCE_INQUIRY,
            0,
            account.getBalance()
        );
        recordTransaction(account.getAccountNumber(), txn);
        return account.getBalance();
    }

    public synchronized Transaction withdrawCash(String cardNumber, String pin, double amount) throws Exception {
        Account account = authenticateCard(cardNumber, pin);

        if (amount > atmCashBalance) {
            throw new Exception("ATM has insufficient cash");
        }

        if (!account.withdraw(amount)) {
            throw new Exception("Insufficient account balance");
        }

        atmCashBalance -= amount;

        Transaction txn = Transaction.create(
            account.getAccountNumber(),
            TransactionType.WITHDRAWAL,
            amount,
            account.getBalance()
        );
        recordTransaction(account.getAccountNumber(), txn);
        return txn;
    }

    public Transaction depositCash(String cardNumber, String pin, double amount) throws Exception {
        Account account = authenticateCard(cardNumber, pin);

        if (amount <= 0) {
            throw new Exception("Invalid deposit amount");
        }

        account.deposit(amount);
        atmCashBalance += amount;

        Transaction txn = Transaction.create(
            account.getAccountNumber(),
            TransactionType.DEPOSIT,
            amount,
            account.getBalance()
        );
        recordTransaction(account.getAccountNumber(), txn);
        return txn;
    }

    public List<Transaction> getTransactionHistory(String cardNumber, String pin) throws Exception {
        Account account = authenticateCard(cardNumber, pin);
        return transactionHistory.getOrDefault(account.getAccountNumber(), new ArrayList<>());
    }

    private void recordTransaction(String accountNumber, Transaction txn) {
        transactionHistory.computeIfAbsent(accountNumber, k -> new ArrayList<>()).add(txn);
    }

    public double getATMCashBalance() {
        return atmCashBalance;
    }
}
