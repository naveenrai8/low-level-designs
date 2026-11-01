package com.lld.matching;

/** Which side of the book an order is on: a BUY (bid) wants to pay at most its price, a SELL (ask) wants to receive at least its price. */
public enum Side {
    BUY,
    SELL;

    /** Returns the side an order of this side trades against. */
    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}
