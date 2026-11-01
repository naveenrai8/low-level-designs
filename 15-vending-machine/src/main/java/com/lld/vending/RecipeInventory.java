package com.lld.vending;

import java.util.HashMap;
import java.util.Map;

/**
 * Coffee-machine stock (follow-up F1, every method synchronized so operators can refill during a sale): a drink is in stock only when every ingredient in its recipe is available in full.
 *
 * <p>Example: a latte needs 30 ml water, 120 ml milk and 18 g coffee. With 100 ml of milk left it is out of stock, even though water and coffee are plentiful. Taking a drink removes all its ingredients together or none of them.
 */
public final class RecipeInventory implements Inventory {

    private final Map<String, Map<String, Integer>> recipes = new HashMap<>();
    private final Map<String, Integer> levels = new HashMap<>();

    /** Defines what one unit of a drink uses, as ingredient name to amount. The recipe must not be empty and every amount must be above zero. */
    public synchronized void defineRecipe(String code, Map<String, Integer> needs) {
        if (needs.isEmpty()) {
            throw new IllegalArgumentException("a recipe needs at least one ingredient");
        }
        needs.forEach((ingredient, amount) -> {
            if (amount <= 0) {
                throw new IllegalArgumentException("amount must be above zero for " + ingredient);
            }
        });
        recipes.put(code, Map.copyOf(needs));
    }

    /** Adds an amount of an ingredient to the machine (a refill). */
    public synchronized void refill(String ingredient, int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        levels.merge(ingredient, amount, Integer::sum);
    }

    /** Returns how much of an ingredient is left, 0 if never loaded. */
    public synchronized int level(String ingredient) {
        return levels.getOrDefault(ingredient, 0);
    }

    /** Returns true when the drink has a recipe and every ingredient is present in the needed amount. */
    @Override
    public synchronized boolean inStock(String code) {
        Map<String, Integer> needs = recipes.get(code);
        if (needs == null) {
            return false;
        }
        return needs.entrySet().stream().allMatch(e -> level(e.getKey()) >= e.getValue());
    }

    /** Removes every ingredient of one drink. Checks first, so a shortage on any ingredient leaves all levels untouched. */
    @Override
    public synchronized void take(String code) {
        if (!inStock(code)) {
            throw new IllegalStateException("sold out: " + code);
        }
        recipes.get(code).forEach((ingredient, amount) -> levels.merge(ingredient, -amount, Integer::sum));
    }
}
