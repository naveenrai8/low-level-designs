package com.lld.ledger;

import java.util.List;
import java.util.Map;

/**
 * The ledger contract. Money is a signed {@code long} in minor units: debit positive, credit negative.
 *
 * <p>Error contract (all {@link LedgerException}, and nothing is posted when one is thrown):
 * <ul>
 * <li>UNBALANCED, TOO_FEW_POSTINGS, ZERO_AMOUNT, OVERFLOW: the draft is badly shaped (thrown when the {@link Draft} is built).
 * <li>UNKNOWN_ACCOUNT, MIXED_CURRENCY: a posting names a missing account, or the accounts are not all in one currency.
 * <li>INSUFFICIENT_FUNDS: the entry (not a reversal) would push a non-overdraft account's available balance below zero.
 * <li>IDEMPOTENCY_CONFLICT: the key was used before with a different draft.
 * <li>UNKNOWN_ENTRY, ALREADY_REVERSED: a reversal of an entry that does not exist or was already reversed.
 * <li>ZERO_AMOUNT also covers a draft whose postings cancel out per account (for example +5 and -5 on one account).
 * <li>DUPLICATE_ACCOUNT: the account id is already open.
 * </ul>
 * <p>There is no update and no delete. A mistake is fixed with {@link #reverse}.
 */
public interface Ledger {

    /**
     * Opens an account with a zero balance.
     *
     * @param account the account to open
     * @throws LedgerException DUPLICATE_ACCOUNT if the id is taken
     */
    void openAccount(Account account);

    /**
     * Appends an entry and updates the balances of every account it touches, all as one atomic step.
     *
     * @param draft the balanced postings
     * @return the posted entry
     */
    Entry post(Draft draft);

    /**
     * Same as {@link #post(Draft)} but safe to retry: the same key with the same draft returns the original entry and posts nothing more.
     *
     * @param draft the balanced postings
     * @param idempotencyKey the caller's key for this logical operation
     * @return the posted (or originally posted) entry
     */
    Entry post(Draft draft, String idempotencyKey);

    /**
     * Cancels an entry by appending its mirror image. The original stays in the journal. A reversal may overdraw an account, because undoing a posting must not be blocked.
     *
     * @param entryId the id of the entry to cancel
     * @return the reversing entry
     */
    Entry reverse(String entryId);

    /**
     * Gives the signed balance (debits minus credits).
     *
     * @param accountId the account
     * @return the balance
     */
    long balance(String accountId);

    /**
     * Gives the balance on the account's normal side, so a liability that is owed 100 shows 100 and not -100.
     *
     * @param accountId the account
     * @return the natural balance
     */
    long naturalBalance(String accountId);

    /**
     * Gives the natural balance minus the money held by active authorisations.
     *
     * @param accountId the account
     * @return the spendable amount
     */
    long available(String accountId);

    /**
     * Gives the signed balance as it was just after the entry with the given sequence number.
     *
     * @param accountId the account
     * @param seq the journal position
     * @return the balance at that point, 0 before the account's first entry
     */
    long balanceAt(String accountId, long seq);

    /**
     * Adds up the signed balances of all accounts in one currency, with all those accounts locked so no post is half seen. It must always be 0.
     *
     * @param currency the currency code
     * @return the sum
     */
    long trialBalance(String currency);

    /**
     * Returns the whole journal in order.
     *
     * @return an unmodifiable copy
     */
    List<Entry> journal();

    /**
     * Recomputes every account's signed balance from the journal alone, ignoring the running balances. Used to audit the projection.
     *
     * @return account id to balance
     */
    Map<String, Long> rebuild();
}
