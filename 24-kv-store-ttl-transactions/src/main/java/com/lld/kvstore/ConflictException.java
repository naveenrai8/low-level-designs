package com.lld.kvstore;

/**
 * Thrown when an outermost commit loses a race: another session committed a change to one of the same keys after this transaction began (first committer wins). The losing transaction is rolled back; the caller can retry it.
 */
public final class ConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception naming the key that was changed under us. */
    public ConflictException(String key) {
        super("write conflict on key '" + key + "': another session committed first");
    }
}
