package com.learning.coffeemachine.controller;

import com.learning.coffeemachine.model.CoffeeType;
import com.learning.coffeemachine.model.Ingredient;
import com.learning.coffeemachine.service.CoffeeMachineService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/coffee")
public class CoffeeController {
    private final CoffeeMachineService service;

    public CoffeeController(CoffeeMachineService service) {
        this.service = service;
    }

    @PostMapping("/brew")
    public ResponseEntity<String> brewCoffee(
            @RequestParam CoffeeType type,
            @RequestParam double payment) {
        try {
            String result = service.makeCoffee(type, payment);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/inventory")
    public ResponseEntity<Map<Ingredient, Integer>> getInventory() {
        return ResponseEntity.ok(service.getInventory());
    }

    @GetMapping("/alerts")
    public ResponseEntity<Map<Ingredient, Boolean>> getLowStockAlerts() {
        return ResponseEntity.ok(service.getLowStockAlerts());
    }

    @PostMapping("/refill")
    public ResponseEntity<String> refill(
            @RequestParam Ingredient ingredient,
            @RequestParam int amount) {
        service.refill(ingredient, amount);
        return ResponseEntity.ok("Refilled " + ingredient);
    }
}
