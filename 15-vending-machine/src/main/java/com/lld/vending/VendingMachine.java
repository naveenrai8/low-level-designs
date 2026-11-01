package com.lld.vending;

/**
 * A vending machine serving one customer at a time. Money is {@code long} minor units (cents).
 *
 * <p>Error contract: a call that is not allowed in the current state throws {@link IllegalTransitionException} and changes nothing. A bad argument throws IllegalArgumentException and changes nothing. A product that is not in stock throws {@link SoldOutException}. A failed sale (no change, jam) is not an exception: it comes back as a {@link SaleResult} with the money returned.
 */
public interface VendingMachine {

    /** Returns the current state. */
    State state();

    /** Returns the money inserted so far in the current session, 0 when idle. */
    long balance();

    /** IDLE to SELECTING. Picks a product. Throws IllegalArgumentException for an unknown code and SoldOutException when it is not in stock. */
    void select(String code);

    /** Accepts one coin while SELECTING and returns the new balance. Once the balance covers the price the state becomes PAID. Throws IllegalArgumentException for a coin the machine does not take. */
    long insert(long coin);

    /** Gives back everything inserted so far and returns to IDLE. Allowed while SELECTING or PAID. */
    Payout cancel();

    /** Presses the dispense button while PAID. Sells the product, or refunds everything if change cannot be made or the dispenser jams. */
    SaleResult dispense();

    /** Operator action: puts more coins of one denomination into the change box. Allowed in any state. */
    void loadCoins(long coin, int count);

    /** Operator view: the coins currently in the change box, not counting a session in progress. */
    Payout coinBox();
}
