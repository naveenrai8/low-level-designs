package com.lld.mistakes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <b>Bug (M8).</b> The invariant is "at most {@code maxItems} lines", enforced in {@link #add}.
 * <p>{@link #items()} hands out the internal list, so any caller can bypass {@code add} and break the invariant.
 * <p>{@link #itemsView()} is the reviews' fix, {@code Collections.unmodifiableList}. It blocks writes, but it is a live <em>view</em>: a caller that kept it sees later changes, and iterating it while the cart changes throws {@code ConcurrentModificationException}.
 */
public final class LeakyCart {

    private final List<LineItem> items = new ArrayList<>();
    private final int maxItems;

    /**
     * Creates an empty cart that holds at most {@code maxItems} lines.
     */
    public LeakyCart(int maxItems) {
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
     * BUG: returns the cart's own list, so a caller can add past the limit without going through {@code add}.
     */
    public List<LineItem> items() {
        return items;                                  // BUG: internal state escapes
    }

    /**
     * Returns a read-only wrapper over the cart's own list. Half a fix: the caller cannot write, but the wrapper is live and shows later changes.
     */
    public List<LineItem> itemsView() {
        return Collections.unmodifiableList(items);    // half a fix: read-only, but live
    }
}
