package com.lld.matching;

import java.util.List;

/**
 * The contract of a one-instrument limit order book. It is NOT thread-safe: one thread owns it (see {@link MatchingEngine}).
 * <p>Every accepted command is appended to {@link #log()}. A rejected command throws {@link IllegalArgumentException} (see {@link Command}) and leaves the book, the trade list and the log exactly as they were.
 */
public interface Book {

    /** Applies one command, returns what it did, and appends it to the log. Throws {@link IllegalArgumentException} for a bad command, changing nothing. */
    Result apply(Command command);

    /** Returns the resting orders of one side in priority order: best price first, and within a price the oldest first. */
    List<OrderView> resting(Side side);

    /** Returns every order ever accepted, in the order they were first accepted (filled and cancelled ones included). */
    List<OrderView> orders();

    /** Returns every trade so far, oldest first. */
    List<Trade> trades();

    /** Returns every accepted command so far, oldest first. Replaying it into a fresh book rebuilds an identical book. */
    List<Command> log();

    /** Returns the book as text: bids best-first, then asks best-first, one line per price level with its orders in queue order. Two equal books give equal strings. */
    default String snapshot() {
        StringBuilder sb = new StringBuilder("BIDS\n");
        appendLevels(sb, resting(Side.BUY));
        sb.append("ASKS\n");
        appendLevels(sb, resting(Side.SELL));
        return sb.toString();
    }

    /** Writes one line per price level, e.g. {@code 100 a:5 b:2}, for orders already sorted in priority order. */
    private static void appendLevels(StringBuilder sb, List<OrderView> orders) {
        long current = -1;
        for (OrderView o : orders) {
            if (o.price() != current) {
                if (current != -1) {
                    sb.append('\n');
                }
                sb.append(o.price());
                current = o.price();
            }
            sb.append(' ').append(o.id()).append(':').append(o.resting());
        }
        if (current != -1) {
            sb.append('\n');
        }
    }
}
