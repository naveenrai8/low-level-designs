package com.lld.splitwise;

/**
 * One payment instruction: {@code from} pays {@code to} the given amount (minor units, always above 0).
 *
 * @param from the person who pays
 * @param to the person who receives
 * @param amount minor units, above 0
 */
public record Transfer(String from, String to, long amount) {

    /** Rejects a non-positive amount or a person paying themselves. */
    public Transfer {
        if (amount <= 0 || from.equals(to)) {
            throw new IllegalArgumentException("bad transfer " + from + " -> " + to + " " + amount);
        }
    }
}
