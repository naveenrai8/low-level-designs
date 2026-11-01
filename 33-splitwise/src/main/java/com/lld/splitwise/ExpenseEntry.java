package com.lld.splitwise;

import java.time.Instant;
import java.util.Map;

/**
 * "Payer paid total, and these people owe these shares." The shares add up to the total exactly (the strategy guarantees it).
 *
 * @param seq position in the history
 * @param at time written
 * @param payer who paid the whole bill
 * @param currency three-letter code, for example USD
 * @param total minor units paid
 * @param shares user id to the share that person owes
 */
public record ExpenseEntry(long seq, Instant at, String payer, String currency, long total, Map<String, Long> shares) implements Entry {

    /** Makes the shares map unmodifiable so history cannot change after the fact. */
    public ExpenseEntry {
        shares = Map.copyOf(shares);
    }
}
