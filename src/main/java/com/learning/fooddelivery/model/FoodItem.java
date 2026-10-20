package com.learning.fooddelivery.model;

public class FoodItem {
    private final String itemId;
    private final String name;
    private final double price;

    public FoodItem(String itemId, String name, double price) {
        this.itemId = itemId;
        this.name = name;
        this.price = price;
    }

    public String getItemId() { return itemId; }
    public String getName() { return name; }
    public double getPrice() { return price; }
}
