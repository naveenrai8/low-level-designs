package com.lld.splitwise;

import java.time.Instant;

/**
 * A settle-up: {@code from} really paid {@code to} money outside the app. Recorded as a new line, never as an edit of old expenses.
 *
 * @param seq position in the history
 * @param at time written
 * @param from who paid
 * @param to who received
 * @param currency three-letter code
 * @param amount minor units, above 0
 */
public record PaymentEntry(long seq, Instant at, String from, String to, String currency, long amount) implements Entry {
}
