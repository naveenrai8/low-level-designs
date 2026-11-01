package com.lld.splitwise;

import java.util.List;
import java.util.Map;

/**
 * A group of friends sharing expenses. Everything is derived from an append-only history of expenses and payments.
 *
 * <p>Error contract: methods throw {@link IllegalArgumentException} for a person who is not a member, a bad currency code, a non-positive amount, a missing exchange rate, or a split the strategy rejects (the group also re-checks what a strategy returns: members only, no negative share, exact sum). A balance that would overflow a long throws {@link ArithmeticException}. Either way a rejected call changes nothing: no history line, no balance moves. Currency arguments are checked on every method, reads included.
 *
 * <p>Invariant: in every currency the nets of all members add up to exactly 0.
 */
public interface ExpenseGroup {

    /**
     * Records that {@code payer} paid {@code total} minor units, and splits it with {@code strategy} over the people named in {@code input}.
     *
     * @return the history line written
     * @throws IllegalArgumentException see the interface contract; also if a person in {@code input} is not a member
     */
    ExpenseEntry addExpense(String payer, String currency, long total, SplitStrategy strategy, Map<String, Long> input);

    /**
     * Records that {@code from} really paid {@code to} some money. It is a new history line, not an edit.
     *
     * @return the history line written
     */
    PaymentEntry recordPayment(String from, String to, String currency, long amount);

    /** Net per member in one currency: positive means owed money, negative means owes. Adds up to 0. Members with no activity show 0. */
    Map<String, Long> nets(String currency);

    /** Who owes whom in one currency, pair by pair, as transfers (each pair appears at most once, in one direction). */
    List<Transfer> pairwise(String currency);

    /** The fewest-looking payments (greedy) that settle one currency. */
    List<Transfer> simplify(String currency);

    /**
     * Settles all currencies at once in {@code target}: each currency's nets are converted at the injected rate (rounding handled so each still adds up to 0), added per person, then simplified.
     *
     * @throws IllegalArgumentException if a needed rate is missing
     */
    List<Transfer> settleUpIn(String target);

    /** A copy of the history, oldest first. */
    List<Entry> history();
}
