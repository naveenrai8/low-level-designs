package com.learning.shopping.model;

import java.util.concurrent.atomic.AtomicInteger;

public class Product {
    private final String productId;
    private final String name;
    private final String category;
    private final double price;
    private final AtomicInteger stock;

    public Product(String productId, String name, String category, double price, int stock) {
        this.productId = productId;
        this.name = name;
        this.category = category;
        this.price = price;
        this.stock = new AtomicInteger(stock);
    }

    public boolean reserveStock(int quantity) {
        return stock.updateAndGet(current ->
            current >= quantity ? current - quantity : current
        ) >= 0;
    }

    public void restoreStock(int quantity) {
        stock.addAndGet(quantity);
    }

    public String getProductId() { return productId; }
    public String getName() { return name; }
    public double getPrice() { return price; }
    public int getStock() { return stock.get(); }
}
