package com.lld.idgen;

/**
 * Hands out unique string IDs whose alphabetical order equals the order they were created in.
 *
 * <p>Examples are UUIDv7 (36 characters, hex) and ULID (26 characters, Crockford base32). Sorting the strings as plain text gives creation order, so a database index on them stays append-mostly.
 *
 * <p>Contract: implementations are thread-safe, and each returned string is strictly greater (as text) than every earlier one from the same instance.
 */
public interface StringIdGenerator {

    /** Returns the next ID. It is greater, as text, than every ID this generator returned before. */
    String next();
}
