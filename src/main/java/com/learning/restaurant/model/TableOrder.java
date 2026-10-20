package com.learning.restaurant.model;

import java.util.List;
import java.util.UUID;

public class TableOrder {
    private final String orderId;
    private final int tableNumber;
    private final List<String> itemIds;
    private final double totalAmount;
    private volatile OrderStatus status;

    public TableOrder(int tableNumber, List<String> itemIds, double totalAmount) {
        this.orderId = UUID.randomUUID().toString();
        this.tableNumber = tableNumber;
        this.itemIds = itemIds;
        this.totalAmount = totalAmount;
        this.status = OrderStatus.PENDING;
    }

    public synchronized void updateStatus(OrderStatus newStatus) {
        this.status = newStatus;
    }

    public String getOrderId() { return orderId; }
    public int getTableNumber() { return tableNumber; }
    public List<String> getItemIds() { return itemIds; }
    public double getTotalAmount() { return totalAmount; }
    public OrderStatus getStatus() { return status; }
}
