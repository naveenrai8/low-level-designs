package com.learning.vendingmachine.controller;

import com.learning.vendingmachine.model.Money;
import com.learning.vendingmachine.model.Product;
import com.learning.vendingmachine.service.VendingMachineService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Collection;
import java.util.Map;

@RestController
@RequestMapping("/api/vending")
public class VendingMachineController {
    private final VendingMachineService service;

    public VendingMachineController(VendingMachineService service) {
        this.service = service;
    }

    @PostMapping("/select/{productId}")
    public ResponseEntity<String> selectProduct(@PathVariable String productId) {
        try {
            service.selectProduct(productId);
            return ResponseEntity.ok("Product selected");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/insert")
    public ResponseEntity<String> insertMoney(@RequestParam Money money) {
        service.insertMoney(money);
        return ResponseEntity.ok("Money inserted");
    }

    @PostMapping("/dispense")
    public ResponseEntity<Map<String, Object>> dispense() {
        try {
            return ResponseEntity.ok(service.dispense());
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/cancel")
    public ResponseEntity<Double> cancel() {
        return ResponseEntity.ok(service.cancel());
    }

    @GetMapping("/inventory")
    public ResponseEntity<Collection<Product>> getInventory() {
        return ResponseEntity.ok(service.getInventory());
    }
}
