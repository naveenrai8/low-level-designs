package com.lld.mistakes;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>Fix (M8).</b> {@link #items()} returns a snapshot ({@code List.copyOf}): unmodifiable, detached from later changes, and safe to iterate.
 * <p>The cost is O(n) per call. If that matters, return a stream, or keep the internal list as an immutable value and swap it on write.
 */
public final class Cart {

    private final List<LineItem> items = new ArrayList<>();
    private final int maxItems;

    /**
     * Creates an empty cart that holds at most {@code maxItems} lines.
     */
    public Cart(int maxItems) {
        this.maxItems = maxItems;
    }

    /**
     * Adds a line. Throws {@code IllegalStateException} if the cart is full.
     */
    public void add(LineItem item) {
        if (items.size() >= maxItems) {
            throw new IllegalStateException("cart full");
        }
        items.add(item);
    }

    /**
     * Returns a frozen copy of the lines. Later changes to the cart do not show up in it, and the caller cannot change the cart through it.
     */
    public List<LineItem> items() {
        return List.copyOf(items);
    }

    /**
     * Turns the cart into an order. The order copies the list itself, so it does not change when the cart does.
     */
    public OrderSnapshot checkout(String orderId) {
        return new OrderSnapshot(orderId, items);      // the record copies defensively
    }
}
