package com.learning.wallet.model;

import java.util.concurrent.locks.ReentrantLock;

public class Wallet {
    private final String walletId;
    private final String userId;
    private volatile double balance;
    private final ReentrantLock lock = new ReentrantLock();

    public Wallet(String walletId, String userId) {
        this.walletId = walletId;
        this.userId = userId;
        this.balance = 0.0;
    }

    public boolean debit(double amount) {
        lock.lock();
        try {
            if (amount <= balance) {
                balance -= amount;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    public void credit(double amount) {
        lock.lock();
        try {
            balance += amount;
        } finally {
            lock.unlock();
        }
    }

    public String getWalletId() { return walletId; }
    public double getBalance() { return balance; }
}
