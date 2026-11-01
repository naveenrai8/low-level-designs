package com.lld.ledger;

/**
 * One line of an entry: an account and a signed amount in minor units (cents).
 * <p>Positive means debit, negative means credit. Zero is never allowed.
 *
 * @param accountId the account this line changes
 * @param amount the signed amount: debit positive, credit negative
 */
public record Posting(String accountId, long amount) {

    /**
     * Checks the fields. A blank account id, an amount of zero, or Long.MIN_VALUE (which cannot be negated for a reversal) throws {@link LedgerException}.
     */
    public Posting {
        if (accountId == null || accountId.isBlank()) {
            throw new LedgerException(LedgerException.Reason.BAD_ARGUMENT, "posting needs an account id");
        }
        if (amount == 0) {
            throw new LedgerException(LedgerException.Reason.ZERO_AMOUNT, "a posting of zero is meaningless");
        }
        if (amount == Long.MIN_VALUE) {
            throw new LedgerException(LedgerException.Reason.OVERFLOW, "Long.MIN_VALUE cannot be negated");
        }
    }

    /**
     * Makes a debit line.
     *
     * @param accountId the account to debit
     * @param amount a positive amount in minor units
     * @return the posting
     */
    public static Posting debit(String accountId, long amount) {
        if (amount <= 0) {
            throw new LedgerException(LedgerException.Reason.ZERO_AMOUNT, "debit amount must be positive");
        }
        return new Posting(accountId, amount);
    }

    /**
     * Makes a credit line.
     *
     * @param accountId the account to credit
     * @param amount a positive amount in minor units (stored as a negative number)
     * @return the posting
     */
    public static Posting credit(String accountId, long amount) {
        if (amount <= 0) {
            throw new LedgerException(LedgerException.Reason.ZERO_AMOUNT, "credit amount must be positive");
        }
        return new Posting(accountId, -amount);
    }
}
