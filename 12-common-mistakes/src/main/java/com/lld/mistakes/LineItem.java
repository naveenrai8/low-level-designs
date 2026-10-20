package com.lld.mistakes;

/** Immutable by construction: a record with only immutable components. */
public record LineItem(String sku, long unitPriceMinor, int quantity) {

    public LineItem {
        if (quantity <= 0 || unitPriceMinor < 0) {
            throw new IllegalArgumentException("bad line item: " + sku);
        }
    }

    public long totalMinor() {
        return Math.multiplyExact(unitPriceMinor, quantity);
    }
}
