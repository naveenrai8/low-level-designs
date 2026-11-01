package com.lld.auction;

/**
 * What is for sale. All money is {@code long} minor units (cents).
 *
 * @param id        the item id, never blank
 * @param seller    who owns it; the seller may never bid
 * @param reserve   the secret minimum the seller accepts; 0 means no reserve
 * @param increment the smallest step a price may rise by, always above 0
 */
public record Item(String id, String seller, long reserve, long increment) {

    /** Checks the fields so a bad item can never exist. Throws {@link IllegalArgumentException} for a blank id or seller, a negative reserve or an increment of 0 or less. */
    public Item {
        if (id == null || id.isBlank() || seller == null || seller.isBlank()) {
            throw new IllegalArgumentException("id and seller must not be blank");
        }
        if (reserve < 0 || increment <= 0) {
            throw new IllegalArgumentException("reserve must be >= 0 and increment > 0");
        }
    }
}
