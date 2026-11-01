package com.lld.ledger;

/**
 * The five kinds of account. Each has a "normal side": the side (debit or credit) that makes its balance grow.
 * <p>Assets and expenses grow with debits. Liabilities, equity and revenue grow with credits.
 */
public enum AccountType {
    /** Things the business owns, such as cash. */
    ASSET(true),
    /** Costs, such as fees paid. */
    EXPENSE(true),
    /** Things the business owes, such as a customer balance. */
    LIABILITY(false),
    /** The owners' stake. */
    EQUITY(false),
    /** Income earned. */
    REVENUE(false);

    private final boolean debitNormal;

    /**
     * Builds one constant.
     *
     * @param debitNormal true when a debit makes this kind of account grow
     */
    AccountType(boolean debitNormal) {
        this.debitNormal = debitNormal;
    }

    /**
     * Says which side makes this account grow.
     *
     * @return true for assets and expenses, false for liabilities, equity and revenue
     */
    public boolean isDebitNormal() {
        return debitNormal;
    }
}
