package com.lld.popularity;

/** Thrown when a new tenant is refused because the tracker already holds the maximum number of tenants. Existing tenants are never evicted to make room. */
public final class TenantLimitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with the limit that was hit. */
    public TenantLimitException(int max) {
        super("tenant limit reached: " + max);
    }
}
