package com.lld.vending;

/** Thrown when the customer picks a product the machine cannot supply right now. Nothing changes: the machine stays IDLE and has taken no money. */
public final class SoldOutException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** Names the product that is out of stock. */
    public SoldOutException(String code) {
        super("sold out: " + code);
    }
}
