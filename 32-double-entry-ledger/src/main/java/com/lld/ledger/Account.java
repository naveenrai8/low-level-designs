package com.lld.ledger;

/**
 * One account: an id, a type, one currency, and whether it may go below zero.
 *
 * @param id the unique name of the account
 * @param type asset, liability, equity, revenue or expense
 * @param currency the currency code; every posting to this account is in this currency
 * @param overdraftAllowed true when the ledger may let the natural balance go below zero (for example a bank cash account)
 */
public record Account(String id, AccountType type, String currency, boolean overdraftAllowed) {

    /**
     * Checks the fields. A blank id or currency, or a null type, throws {@link LedgerException} with reason BAD_ARGUMENT.
     */
    public Account {
        if (id == null || id.isBlank() || currency == null || currency.isBlank() || type == null) {
            throw new LedgerException(LedgerException.Reason.BAD_ARGUMENT, "account needs id, type and currency");
        }
    }

    /**
     * Makes an account that may not go below zero.
     *
     * @param id the account id
     * @param type the account type
     * @param currency the currency code
     * @return the account
     */
    public static Account of(String id, AccountType type, String currency) {
        return new Account(id, type, currency, false);
    }

    /**
     * Makes an account that is allowed to go below zero.
     *
     * @param id the account id
     * @param type the account type
     * @param currency the currency code
     * @return the account
     */
    public static Account overdraft(String id, AccountType type, String currency) {
        return new Account(id, type, currency, true);
    }
}
