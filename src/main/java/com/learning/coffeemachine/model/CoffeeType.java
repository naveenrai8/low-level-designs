package com.learning.coffeemachine.model;

import java.util.Map;

public enum CoffeeType {
    ESPRESSO(2.50, Map.of(Ingredient.WATER, 30, Ingredient.COFFEE, 18)),
    CAPPUCCINO(3.50, Map.of(Ingredient.WATER, 30, Ingredient.MILK, 60, Ingredient.COFFEE, 18)),
    LATTE(4.00, Map.of(Ingredient.WATER, 30, Ingredient.MILK, 120, Ingredient.COFFEE, 18));

    private final double price;
    private final Map<Ingredient, Integer> recipe;

    CoffeeType(double price, Map<Ingredient, Integer> recipe) {
        this.price = price;
        this.recipe = recipe;
    }

    public double getPrice() { return price; }
    public Map<Ingredient, Integer> getRecipe() { return recipe; }
}
