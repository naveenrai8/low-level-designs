package com.learning.fooddelivery.controller;

import com.learning.fooddelivery.dto.OrderRequest;
import com.learning.fooddelivery.dto.RestaurantRequest;
import com.learning.fooddelivery.model.*;
import com.learning.fooddelivery.service.FoodDeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/fooddelivery")
@Tag(name = "Food Delivery", description = "Food delivery operations")
public class FoodDeliveryController {

    private final FoodDeliveryService foodDeliveryService;

    public FoodDeliveryController(FoodDeliveryService foodDeliveryService) {
        this.foodDeliveryService = foodDeliveryService;
    }

    @PostMapping("/restaurants")
    @Operation(summary = "Add a restaurant")
    public ResponseEntity<Restaurant> addRestaurant(@Valid @RequestBody RestaurantRequest request) {
        Restaurant restaurant = new Restaurant(
            UUID.randomUUID().toString(),
            request.name(),
            request.location()
        );
        foodDeliveryService.addRestaurant(restaurant);
        return ResponseEntity.status(HttpStatus.CREATED).body(restaurant);
    }

    @GetMapping("/restaurants")
    @Operation(summary = "Get all restaurants")
    public ResponseEntity<List<Restaurant>> getAllRestaurants() {
        return ResponseEntity.ok(foodDeliveryService.getAllRestaurants());
    }

    @GetMapping("/restaurants/search")
    @Operation(summary = "Search restaurants by location")
    public ResponseEntity<List<Restaurant>> searchRestaurants(@RequestParam String location) {
        return ResponseEntity.ok(foodDeliveryService.searchRestaurants(location));
    }

    @GetMapping("/restaurants/{restaurantId}")
    @Operation(summary = "Get restaurant details")
    public ResponseEntity<Restaurant> getRestaurant(@PathVariable String restaurantId) {
        Restaurant restaurant = foodDeliveryService.getRestaurant(restaurantId);
        return restaurant != null ? ResponseEntity.ok(restaurant) : ResponseEntity.notFound().build();
    }

    @PostMapping("/orders")
    @Operation(summary = "Place an order")
    public ResponseEntity<?> placeOrder(@Valid @RequestBody OrderRequest request) {
        try {
            Order order = foodDeliveryService.placeOrder(
                request.restaurantId(), request.customerId(), request.itemIds()
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(order);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/orders/{orderId}")
    @Operation(summary = "Get order details")
    public ResponseEntity<Order> getOrder(@PathVariable String orderId) {
        Order order = foodDeliveryService.getOrder(orderId);
        return order != null ? ResponseEntity.ok(order) : ResponseEntity.notFound().build();
    }

    @PutMapping("/orders/{orderId}/status")
    @Operation(summary = "Update order status")
    public ResponseEntity<?> updateOrderStatus(
            @PathVariable String orderId,
            @RequestParam OrderStatus status) {
        try {
            foodDeliveryService.updateOrderStatus(orderId, status);
            return ResponseEntity.ok("Order status updated");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
