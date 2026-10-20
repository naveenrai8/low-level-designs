package com.learning.atm.model;

import java.util.concurrent.locks.ReentrantLock;

public class Account {
    private final String accountNumber;
    private final String cardNumber;
    private final String pin;
    private volatile double balance;
    private final ReentrantLock lock = new ReentrantLock();

    public Account(String accountNumber, String cardNumber, String pin, double initialBalance) {
        this.accountNumber = accountNumber;
        this.cardNumber = cardNumber;
        this.pin = pin;
        this.balance = initialBalance;
    }

    public boolean validatePin(String inputPin) {
        return this.pin.equals(inputPin);
    }

    public double getBalance() {
        return balance;
    }

    public boolean withdraw(double amount) {
        lock.lock();
        try {
            if (amount <= 0 || amount > balance) {
                return false;
            }
            balance -= amount;
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void deposit(double amount) {
        lock.lock();
        try {
            if (amount > 0) {
                balance += amount;
            }
        } finally {
            lock.unlock();
        }
    }

    public String getAccountNumber() { return accountNumber; }
    public String getCardNumber() { return cardNumber; }
}
