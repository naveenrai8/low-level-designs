package com.lld.vending;

/** Everything that can try to move the machine from one {@link State} to another. The first four come from the customer, the last two come from the machine itself. */
public enum Event {
    /** The customer picks a product. */
    SELECT,
    /** The customer drops a coin in. */
    INSERT,
    /** The customer asks for their money back. */
    CANCEL,
    /** The customer presses the dispense button. */
    DISPENSE,
    /** The machine reports that the product dropped out correctly. */
    COMPLETE,
    /** The machine reports that the dispenser jammed. */
    JAM
}
