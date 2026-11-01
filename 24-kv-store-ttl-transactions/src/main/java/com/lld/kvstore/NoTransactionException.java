package com.lld.kvstore;

/**
 * Thrown by {@code commit()} or {@code rollback()} when the session has no open transaction.
 */
public final class NoTransactionException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception for the given operation name ("commit" or "rollback"). */
    public NoTransactionException(String operation) {
        super("cannot " + operation + ": no transaction is open");
    }
}
