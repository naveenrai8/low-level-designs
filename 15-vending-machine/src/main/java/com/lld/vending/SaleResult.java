package com.lld.vending;

/**
 * What happened when the customer pressed dispense.
 *
 * <p>Money always balances: everything the customer put in equals {@code returned.total() + kept}. See {@link #inserted()}.
 *
 * @param status      how the sale ended
 * @param productCode the product that dropped, or null when nothing was dispensed
 * @param returned    the coins given back (change on a sale, the full refund otherwise)
 * @param kept        the money the machine keeps, which is the price on a sale and 0 otherwise
 */
public record SaleResult(Status status, String productCode, Payout returned, long kept) {

    /** The four ways a dispense press can end. */
    public enum Status {
        /** The product dropped and change (if any) was returned. */
        SOLD,
        /** The machine could not make the change, so it refused before touching the product and returned everything. */
        REFUSED_NO_CHANGE,
        /** The product ran out between select and dispense (an operator restocked to zero), so the machine refused before the motor and returned everything. */
        REFUSED_OUT_OF_STOCK,
        /** The dispenser jammed, so the product was not delivered and everything was returned. */
        JAMMED_REFUNDED
    }

    /** Returns everything the customer put in this session, which is by definition what came back plus what was kept. */
    public long inserted() {
        return returned.total() + kept;
    }
}
