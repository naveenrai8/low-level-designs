package com.lld.ledger;

/**
 * The one error type of the ledger. The {@link Reason} says what went wrong, so callers switch on the reason and never parse the message.
 * <p>Every reason is a caller mistake or a business refusal. Nothing is posted when one is thrown.
 */
public final class LedgerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** What went wrong. */
    public enum Reason {
        BAD_ARGUMENT, TOO_FEW_POSTINGS, ZERO_AMOUNT, UNBALANCED, OVERFLOW, MIXED_CURRENCY, UNKNOWN_ACCOUNT, DUPLICATE_ACCOUNT,
        INSUFFICIENT_FUNDS, IDEMPOTENCY_CONFLICT, UNKNOWN_ENTRY, ALREADY_REVERSED,
        UNKNOWN_HOLD, HOLD_NOT_ACTIVE, HOLD_EXPIRED, CAPTURE_TOO_LARGE, INTERRUPTED
    }

    private final Reason reason;

    /**
     * Builds the exception.
     *
     * @param reason the machine-readable cause
     * @param message a human-readable explanation
     */
    public LedgerException(Reason reason, String message) {
        super(reason + ": " + message);
        this.reason = reason;
    }

    /**
     * Tells what went wrong.
     *
     * @return the reason
     */
    public Reason reason() {
        return reason;
    }
}
