package com.lld.vending;

import java.time.Instant;

/**
 * One fact the machine reports to the outside world (follow-up F3).
 *
 * @param at          when it happened, from the injected clock
 * @param kind        what happened
 * @param productCode the product involved, or null
 * @param amount      money involved in minor units: the price kept on a sale, the amount refunded on a refusal or jam, 0 for a stock fault
 */
public record MachineEvent(Instant at, Kind kind, String productCode, long amount) {

    /** The things worth reporting to a remote operator. */
    public enum Kind {
        /** A product was sold. */
        SALE,
        /** A sale was refused because change could not be made. */
        REFUSED_NO_CHANGE,
        /** A sale was refused because the product ran out after it was selected. */
        REFUSED_OUT_OF_STOCK,
        /** The product dropped but the stock count could not be updated; the sale stood. Someone should check the counts. */
        STOCK_FAULT,
        /** The dispenser jammed and the money was refunded. A real fleet would page someone. */
        JAM
    }
}
