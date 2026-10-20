package com.learning.restaurant.service;

import com.learning.restaurant.model.*;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class RestaurantService {
    private final ConcurrentHashMap<String, MenuItem> menu = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Table> tables = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TableOrder> orders = new ConcurrentHashMap<>();

    public void addMenuItem(MenuItem item) {
        menu.put(item.getItemId(), item);
    }

    public void addTable(Table table) {
        tables.put(table.getTableNumber(), table);
    }

    public List<Table> getAvailableTables() {
        return tables.values().stream()
                .filter(table -> table.getStatus() == TableStatus.AVAILABLE)
                .collect(Collectors.toList());
    }

    public boolean reserveTable(int tableNumber) {
        Table table = tables.get(tableNumber);
        return table != null && table.reserve();
    }

    public TableOrder placeOrder(int tableNumber, List<String> itemIds) throws Exception {
        Table table = tables.get(tableNumber);
        if (table == null) {
            throw new Exception("Table not found");
        }

        double totalAmount = menu.values().stream()
                .filter(item -> itemIds.contains(item.getItemId()) && item.isAvailable())
                .mapToDouble(MenuItem::getPrice)
                .sum();

        TableOrder order = new TableOrder(tableNumber, itemIds, totalAmount);
        orders.put(order.getOrderId(), order);
        table.occupy();
        return order;
    }

    public void updateOrderStatus(String orderId, OrderStatus status) throws Exception {
        TableOrder order = orders.get(orderId);
        if (order == null) {
            throw new Exception("Order not found");
        }
        order.updateStatus(status);

        if (status == OrderStatus.COMPLETED) {
            Table table = tables.get(order.getTableNumber());
            if (table != null) {
                table.free();
            }
        }
    }

    public MenuItem getMenuItem(String itemId) {
        return menu.get(itemId);
    }

    public Table getTable(int tableNumber) {
        return tables.get(tableNumber);
    }

    public TableOrder getOrder(String orderId) {
        return orders.get(orderId);
    }

    public List<MenuItem> getMenu() {
        return List.copyOf(menu.values());
    }
}
