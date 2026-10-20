package com.learning.fooddelivery.service;

import com.learning.fooddelivery.model.*;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class FoodDeliveryService {
    private final ConcurrentHashMap<String, Restaurant> restaurants = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Order> orders = new ConcurrentHashMap<>();

    public void addRestaurant(Restaurant restaurant) {
        restaurants.put(restaurant.getRestaurantId(), restaurant);
    }

    public List<Restaurant> searchRestaurants(String location) {
        return restaurants.values().stream()
                .filter(restaurant -> restaurant.isOpen() &&
                                     restaurant.getLocation().equalsIgnoreCase(location))
                .collect(Collectors.toList());
    }

    public Order placeOrder(String restaurantId, String customerId, List<String> itemIds) throws Exception {
        Restaurant restaurant = restaurants.get(restaurantId);
        if (restaurant == null) throw new Exception("Restaurant not found");
        if (!restaurant.isOpen()) throw new Exception("Restaurant is closed");

        // Calculate total
        double totalAmount = restaurant.getMenu().stream()
                .filter(item -> itemIds.contains(item.getItemId()))
                .mapToDouble(FoodItem::getPrice)
                .sum();

        Order order = new Order(restaurantId, customerId, itemIds, totalAmount);
        orders.put(order.getOrderId(), order);
        return order;
    }

    public void updateOrderStatus(String orderId, OrderStatus status) throws Exception {
        Order order = orders.get(orderId);
        if (order == null) throw new Exception("Order not found");
        order.updateStatus(status);
    }

    public Order getOrder(String orderId) {
        return orders.get(orderId);
    }

    public Restaurant getRestaurant(String restaurantId) {
        return restaurants.get(restaurantId);
    }

    public List<Restaurant> getAllRestaurants() {
        return List.copyOf(restaurants.values());
    }
}
