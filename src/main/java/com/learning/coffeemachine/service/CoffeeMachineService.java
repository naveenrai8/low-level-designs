package com.learning.coffeemachine.service;

import com.learning.coffeemachine.model.CoffeeType;
import com.learning.coffeemachine.model.Ingredient;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class CoffeeMachineService {
    private final ConcurrentHashMap<Ingredient, AtomicInteger> inventory = new ConcurrentHashMap<>();
    private final ReentrantLock brewLock = new ReentrantLock();
    private static final int LOW_STOCK_THRESHOLD = 100;

    public CoffeeMachineService() {
        initializeInventory();
    }

    private void initializeInventory() {
        inventory.put(Ingredient.WATER, new AtomicInteger(5000));
        inventory.put(Ingredient.MILK, new AtomicInteger(3000));
        inventory.put(Ingredient.COFFEE, new AtomicInteger(1000));
        inventory.put(Ingredient.SUGAR, new AtomicInteger(500));
    }

    public String makeCoffee(CoffeeType type, double payment) throws Exception {
        brewLock.lock();
        try {
            if (payment < type.getPrice()) {
                throw new Exception("Insufficient payment");
            }

            if (!checkIngredients(type)) {
                throw new Exception("Insufficient ingredients");
            }

            deductIngredients(type);
            double change = payment - type.getPrice();

            return String.format("Brewing %s... Done! Change: $%.2f", type.name(), change);
        } finally {
            brewLock.unlock();
        }
    }

    private boolean checkIngredients(CoffeeType type) {
        return type.getRecipe().entrySet().stream()
                .allMatch(e -> inventory.get(e.getKey()).get() >= e.getValue());
    }

    private void deductIngredients(CoffeeType type) {
        type.getRecipe().forEach((ingredient, amount) ->
                inventory.get(ingredient).addAndGet(-amount));
    }

    public Map<Ingredient, Integer> getInventory() {
        Map<Ingredient, Integer> current = new ConcurrentHashMap<>();
        inventory.forEach((k, v) -> current.put(k, v.get()));
        return current;
    }

    public Map<Ingredient, Boolean> getLowStockAlerts() {
        Map<Ingredient, Boolean> alerts = new ConcurrentHashMap<>();
        inventory.forEach((k, v) -> alerts.put(k, v.get() < LOW_STOCK_THRESHOLD));
        return alerts;
    }

    public void refill(Ingredient ingredient, int amount) {
        inventory.get(ingredient).addAndGet(amount);
    }
}
