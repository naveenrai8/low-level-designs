package com.lld.mistakes;

/**
 * One line of a cart or order. Immutable by construction: a record with only immutable components.
 */
public record LineItem(String sku, long unitPriceMinor, int quantity) {

    /**
     * Checks the line on creation. Throws {@code IllegalArgumentException} for a quantity of zero or less, or a negative price.
     */
    public LineItem {
        if (quantity <= 0 || unitPriceMinor < 0) {
            throw new IllegalArgumentException("bad line item: " + sku);
        }
    }

    /**
     * Returns price times quantity in minor units. Throws {@code ArithmeticException} on overflow instead of wrapping.
     */
    public long totalMinor() {
        return Math.multiplyExact(unitPriceMinor, quantity);
    }
}
