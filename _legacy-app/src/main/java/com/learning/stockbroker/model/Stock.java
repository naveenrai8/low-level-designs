package com.learning.stockbroker.model;

import java.util.concurrent.atomic.AtomicReference;

public class Stock {
    private final String symbol;
    private final String name;
    private final AtomicReference<Double> price;

    public Stock(String symbol, String name, double initialPrice) {
        this.symbol = symbol;
        this.name = name;
        this.price = new AtomicReference<>(initialPrice);
    }

    public void updatePrice(double newPrice) {
        price.set(newPrice);
    }

    public String getSymbol() { return symbol; }
    public double getPrice() { return price.get(); }
}
