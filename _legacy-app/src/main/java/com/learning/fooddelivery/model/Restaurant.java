package com.learning.fooddelivery.model;

import java.util.ArrayList;
import java.util.List;

public class Restaurant {
    private final String restaurantId;
    private final String name;
    private final String location;
    private final List<FoodItem> menu;
    private volatile boolean isOpen;

    public Restaurant(String restaurantId, String name, String location) {
        this.restaurantId = restaurantId;
        this.name = name;
        this.location = location;
        this.menu = new ArrayList<>();
        this.isOpen = true;
    }

    public void addMenuItem(FoodItem item) {
        menu.add(item);
    }

    public String getRestaurantId() { return restaurantId; }
    public String getName() { return name; }
    public String getLocation() { return location; }
    public List<FoodItem> getMenu() { return new ArrayList<>(menu); }
    public boolean isOpen() { return isOpen; }
}
