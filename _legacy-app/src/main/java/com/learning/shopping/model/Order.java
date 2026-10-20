package com.learning.shopping.model;

import java.time.Instant;
import java.util.List;

public record Order(
    String orderId,
    String userId,
    List<OrderItem> items,
    double totalAmount,
    OrderStatus status,
    Instant createdAt
) {
    public static Order create(String userId, List<OrderItem> items) {
        double total = items.stream().mapToDouble(OrderItem::subtotal).sum();
        return new Order(
            java.util.UUID.randomUUID().toString(),
            userId,
            items,
            total,
            OrderStatus.PENDING,
            Instant.now()
        );
    }
}

record OrderItem(String productId, int quantity, double price) {
    double subtotal() { return quantity * price; }
}
