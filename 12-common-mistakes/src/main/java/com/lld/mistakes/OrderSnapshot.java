package com.lld.mistakes;

import java.util.List;

/**
 * <b>Fix (M8), records.</b> A record is only as immutable as its components. Without the compact
 * constructor's {@code List.copyOf}, the record would share the caller's {@code ArrayList}, so the
 * "immutable" order would change when the cart did. {@code List.copyOf} also rejects
 * {@code null} elements.
 */
public record OrderSnapshot(String orderId, List<LineItem> items) {

    public OrderSnapshot {
        items = List.copyOf(items);
    }

    public long totalMinor() {
        long total = 0;
        for (LineItem i : items) {
            total = Math.addExact(total, i.totalMinor());
        }
        return total;
    }
}
