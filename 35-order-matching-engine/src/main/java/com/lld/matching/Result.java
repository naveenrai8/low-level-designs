package com.lld.matching;

import java.util.List;

/**
 * What one accepted command did.
 *
 * @param orderId   the order the command was about
 * @param outcome   the short summary (see {@link Outcome})
 * @param trades    the trades it caused, in the order they happened
 * @param rested    the quantity now waiting in the book for this order
 * @param cancelled the quantity removed without trading by this command
 */
public record Result(String orderId, Outcome outcome, List<Trade> trades, long rested, long cancelled) {

    /** The short summary of a command. */
    public enum Outcome {
        /** Nothing is left: all of it traded. */
        FILLED,
        /** Some quantity waits in the book. */
        RESTED,
        /** The remainder was removed (cancel, or IOC and market leftovers). */
        CANCELLED,
        /** A fill-or-kill that could not be filled completely, so nothing happened to the book. */
        KILLED
    }

    /** Copies the trade list so a result can never change after it is returned. */
    public Result {
        trades = List.copyOf(trades);
    }
}
