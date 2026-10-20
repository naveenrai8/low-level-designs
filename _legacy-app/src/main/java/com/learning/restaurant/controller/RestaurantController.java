package com.learning.restaurant.controller;

import com.learning.restaurant.dto.MenuItemRequest;
import com.learning.restaurant.dto.TableOrderRequest;
import com.learning.restaurant.model.*;
import com.learning.restaurant.service.RestaurantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/restaurant")
@Tag(name = "Restaurant", description = "Restaurant management operations")
public class RestaurantController {

    private final RestaurantService restaurantService;

    public RestaurantController(RestaurantService restaurantService) {
        this.restaurantService = restaurantService;
    }

    @PostMapping("/menu")
    @Operation(summary = "Add menu item")
    public ResponseEntity<MenuItem> addMenuItem(@Valid @RequestBody MenuItemRequest request) {
        MenuItem item = new MenuItem(
            UUID.randomUUID().toString(),
            request.name(),
            request.category(),
            request.price()
        );
        restaurantService.addMenuItem(item);
        return ResponseEntity.status(HttpStatus.CREATED).body(item);
    }

    @GetMapping("/menu")
    @Operation(summary = "Get menu")
    public ResponseEntity<List<MenuItem>> getMenu() {
        return ResponseEntity.ok(restaurantService.getMenu());
    }

    @PostMapping("/tables")
    @Operation(summary = "Add table")
    public ResponseEntity<Table> addTable(@RequestParam int tableNumber, @RequestParam int capacity) {
        Table table = new Table(tableNumber, capacity);
        restaurantService.addTable(table);
        return ResponseEntity.status(HttpStatus.CREATED).body(table);
    }

    @GetMapping("/tables/available")
    @Operation(summary = "Get available tables")
    public ResponseEntity<List<Table>> getAvailableTables() {
        return ResponseEntity.ok(restaurantService.getAvailableTables());
    }

    @PostMapping("/tables/{tableNumber}/reserve")
    @Operation(summary = "Reserve a table")
    public ResponseEntity<String> reserveTable(@PathVariable int tableNumber) {
        boolean success = restaurantService.reserveTable(tableNumber);
        return success ? ResponseEntity.ok("Table reserved")
                       : ResponseEntity.badRequest().body("Table not available");
    }

    @PostMapping("/orders")
    @Operation(summary = "Place an order")
    public ResponseEntity<?> placeOrder(@Valid @RequestBody TableOrderRequest request) {
        try {
            TableOrder order = restaurantService.placeOrder(request.tableNumber(), request.itemIds());
            return ResponseEntity.status(HttpStatus.CREATED).body(order);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/orders/{orderId}")
    @Operation(summary = "Get order details")
    public ResponseEntity<TableOrder> getOrder(@PathVariable String orderId) {
        TableOrder order = restaurantService.getOrder(orderId);
        return order != null ? ResponseEntity.ok(order) : ResponseEntity.notFound().build();
    }

    @PutMapping("/orders/{orderId}/status")
    @Operation(summary = "Update order status")
    public ResponseEntity<?> updateOrderStatus(
            @PathVariable String orderId,
            @RequestParam OrderStatus status) {
        try {
            restaurantService.updateOrderStatus(orderId, status);
            return ResponseEntity.ok("Order status updated");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
