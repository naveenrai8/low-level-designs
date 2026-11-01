package com.lld.splitwise;

import java.time.Instant;

/**
 * One line of a group's append-only history. Balances are derived from these lines and never edited in place.
 */
public sealed interface Entry permits ExpenseEntry, PaymentEntry {

    /** Position in the group history, starting at 1, assigned under the group lock. */
    long seq();

    /** When the line was written, from the injected clock. */
    Instant at();
}
