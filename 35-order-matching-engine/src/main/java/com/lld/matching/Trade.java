package com.lld.matching;

/**
 * One match between an incoming (taker) order and a resting (maker) order. The price is always the maker's price.
 *
 * @param seq       1, 2, 3, ... in the order trades happened in this book
 * @param takerId   the order that arrived and caused the trade
 * @param makerId   the order that was already waiting in the book
 * @param takerSide the side of the taker
 * @param price     the maker's price in ticks
 * @param qty       the quantity traded
 */
public record Trade(long seq, String takerId, String makerId, Side takerSide, long price, long qty) {

    /** Returns price times quantity (the money value). Throws {@link ArithmeticException} instead of wrapping around. */
    public long notional() {
        return Math.multiplyExact(price, qty);
    }
}
