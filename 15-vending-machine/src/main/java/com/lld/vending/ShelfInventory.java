package com.lld.vending;

import java.util.HashMap;
import java.util.Map;

/** Snack-machine stock: a simple count per product code. Every method is synchronized, so operators can restock while a customer is using the machine. */
public final class ShelfInventory implements Inventory {

    private final Map<String, Integer> counts = new HashMap<>();

    /** Sets how many units of a product sit in its slot. */
    public synchronized void load(String code, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        counts.put(code, count);
    }

    /** Returns how many units are left, 0 for unknown codes. */
    public synchronized int count(String code) {
        return counts.getOrDefault(code, 0);
    }

    /** Returns true when at least one unit is left. */
    @Override
    public synchronized boolean inStock(String code) {
        return count(code) > 0;
    }

    /** Removes one unit, or throws when the slot is empty. */
    @Override
    public synchronized void take(String code) {
        if (!inStock(code)) {
            throw new IllegalStateException("sold out: " + code);
        }
        counts.merge(code, -1, Integer::sum);
    }
}
