package com.lld.vending;

/**
 * One thing the machine sells.
 *
 * @param code  the slot code the customer types, such as "A1"
 * @param name  a human label
 * @param price the price in minor units (cents), always above zero
 */
public record Product(String code, String name, long price) {

    /** Rejects a blank code or a price that is not above zero. */
    public Product {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (price <= 0) {
            throw new IllegalArgumentException("price must be above zero: " + price);
        }
    }
}
