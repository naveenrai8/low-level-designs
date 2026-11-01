package com.lld.cache;

/** Why an entry left the cache. Callers react differently: SIZE means "too small", EXPIRED means "stale". */
public enum RemovalCause {
    /** {@code remove(key)} was called. */
    EXPLICIT,
    /** {@code put} on an existing key replaced this value. */
    REPLACED,
    /** Evicted by the policy to respect the bound, or refused because it alone exceeds the bound. */
    SIZE,
    /** Its time-to-live passed. Noticed lazily (on access) or by {@code cleanUp()}. */
    EXPIRED
}
