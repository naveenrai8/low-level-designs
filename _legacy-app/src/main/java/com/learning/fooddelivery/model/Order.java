package com.learning.fooddelivery.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class Order {
    private final String orderId;
    private final String restaurantId;
    private final String customerId;
    private final List<String> itemIds;
    private final double totalAmount;
    private final Instant orderTime;
    private volatile OrderStatus status;

    public Order(String restaurantId, String customerId, List<String> itemIds, double totalAmount) {
        this.orderId = UUID.randomUUID().toString();
        this.restaurantId = restaurantId;
        this.customerId = customerId;
        this.itemIds = itemIds;
        this.totalAmount = totalAmount;
        this.orderTime = Instant.now();
        this.status = OrderStatus.PLACED;
    }

    public synchronized void updateStatus(OrderStatus newStatus) {
        this.status = newStatus;
    }

    public String getOrderId() { return orderId; }
    public String getRestaurantId() { return restaurantId; }
    public String getCustomerId() { return customerId; }
    public List<String> getItemIds() { return itemIds; }
    public double getTotalAmount() { return totalAmount; }
    public Instant getOrderTime() { return orderTime; }
    public OrderStatus getStatus() { return status; }
}
