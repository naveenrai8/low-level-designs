package com.learning.stockbroker.model;

import java.util.concurrent.ConcurrentHashMap;

public class Portfolio {
    private final String userId;
    private final ConcurrentHashMap<String, Integer> holdings;
    private volatile double balance;

    public Portfolio(String userId, double initialBalance) {
        this.userId = userId;
        this.balance = initialBalance;
        this.holdings = new ConcurrentHashMap<>();
    }

    public synchronized boolean buyStock(String symbol, int quantity, double pricePerShare) {
        double cost = quantity * pricePerShare;
        if (cost > balance) {
            return false;
        }
        balance -= cost;
        holdings.merge(symbol, quantity, Integer::sum);
        return true;
    }

    public synchronized boolean sellStock(String symbol, int quantity, double pricePerShare) {
        Integer currentHolding = holdings.get(symbol);
        if (currentHolding == null || currentHolding < quantity) {
            return false;
        }

        double proceeds = quantity * pricePerShare;
        balance += proceeds;
        holdings.compute(symbol, (k, v) -> v - quantity == 0 ? null : v - quantity);
        return true;
    }

    public String getUserId() { return userId; }
    public double getBalance() { return balance; }
    public ConcurrentHashMap<String, Integer> getHoldings() { return new ConcurrentHashMap<>(holdings); }
}
