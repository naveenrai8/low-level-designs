package com.learning.restaurant.model;

public class MenuItem {
    private final String itemId;
    private final String name;
    private final MenuCategory category;
    private final double price;
    private volatile boolean available;

    public MenuItem(String itemId, String name, MenuCategory category, double price) {
        this.itemId = itemId;
        this.name = name;
        this.category = category;
        this.price = price;
        this.available = true;
    }

    public synchronized void setAvailable(boolean available) {
        this.available = available;
    }

    public String getItemId() { return itemId; }
    public String getName() { return name; }
    public MenuCategory getCategory() { return category; }
    public double getPrice() { return price; }
    public boolean isAvailable() { return available; }
}
