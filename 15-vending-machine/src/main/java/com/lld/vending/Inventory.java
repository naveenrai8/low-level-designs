package com.lld.vending;

/**
 * What the machine can currently supply.
 *
 * <p>The machine asks at select time and again just before the motor runs, and calls {@link #take} only after the product really dropped, so a jam never loses stock.
 *
 * <p>Operators restock directly on the inventory while customers use the machine, so implementations must be thread-safe, and the machine must cope with the answer changing between the question and {@link #take}.
 */
public interface Inventory {

    /** Returns true when one more unit of this product can be supplied right now. */
    boolean inStock(String code);

    /** Uses up one unit of this product. Throws IllegalStateException if it is not in stock. All or nothing: a failure changes nothing. */
    void take(String code);
}
