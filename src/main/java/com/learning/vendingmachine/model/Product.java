package com.learning.vendingmachine.model;

import java.util.concurrent.atomic.AtomicInteger;

public class Product {
    private final String id;
    private final String name;
    private final double price;
    private final AtomicInteger quantity;

    public Product(String id, String name, double price, int initialQuantity) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.quantity = new AtomicInteger(initialQuantity);
    }

    public boolean isAvailable() {
        return quantity.get() > 0;
    }

    public boolean decrementQuantity() {
        return quantity.updateAndGet(current -> current > 0 ? current - 1 : current) >= 0;
    }

    public void addStock(int amount) {
        quantity.addAndGet(amount);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public double getPrice() { return price; }
    public int getQuantity() { return quantity.get(); }
}
