package com.lld.matching;

/** How a new order behaves when it arrives. Only LIMIT can ever rest in the book. */
public enum OrderType {
    /** A limit order, good till cancelled: fills what crosses now and the rest waits in the book at its price. */
    LIMIT,
    /** Immediate-or-cancel with a limit price: fills what crosses now and cancels the rest. */
    IOC,
    /** Fill-or-kill with a limit price: fills the whole quantity right now or does nothing at all. */
    FOK,
    /** No price: sweeps whatever is there, any price. The unfilled rest is cancelled and never waits. */
    MARKET
}
