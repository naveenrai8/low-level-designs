package com.lld.mistakes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <b>Bug (M8).</b> The invariant is "at most {@code maxItems} lines", enforced in {@link #add}.
 * {@link #items()} hands out the internal list, so any caller can bypass {@code add} and the
 * invariant. {@link #itemsView()} is the reviews' fix, {@code Collections.unmodifiableList}. It
 * blocks writes, but it's a live <em>view</em>: a caller that kept it sees later changes, and
 * iterating it while the cart changes throws {@code ConcurrentModificationException}.
 */
public final class LeakyCart {

    private final List<LineItem> items = new ArrayList<>();
    private final int maxItems;

    public LeakyCart(int maxItems) {
        this.maxItems = maxItems;
    }

    public void add(LineItem item) {
        if (items.size() >= maxItems) {
            throw new IllegalStateException("cart full");
        }
        items.add(item);
    }

    public List<LineItem> items() {
        return items;                                  // BUG: internal state escapes
    }

    public List<LineItem> itemsView() {
        return Collections.unmodifiableList(items);    // half a fix: read-only, but live
    }
}
