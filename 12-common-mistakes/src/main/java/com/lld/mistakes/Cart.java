package com.lld.mistakes;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>Fix (M8).</b> {@link #items()} returns a snapshot ({@code List.copyOf}): unmodifiable,
 * detached from later changes, and safe to iterate. The cost is O(n) per call. If that matters,
 * return a stream, or keep the internal list as an immutable value and swap it on write.
 */
public final class Cart {

    private final List<LineItem> items = new ArrayList<>();
    private final int maxItems;

    public Cart(int maxItems) {
        this.maxItems = maxItems;
    }

    public void add(LineItem item) {
        if (items.size() >= maxItems) {
            throw new IllegalStateException("cart full");
        }
        items.add(item);
    }

    public List<LineItem> items() {
        return List.copyOf(items);
    }

    public OrderSnapshot checkout(String orderId) {
        return new OrderSnapshot(orderId, items);      // the record copies defensively
    }
}
