package com.lld.kvstore;

/**
 * One change to apply to the store: set a key to a value with an absolute deadline, or delete it.
 *
 * @param key the key to change
 * @param value the new value, or {@code null} to delete the key
 * @param deadlineMs the moment (epoch milliseconds) the key expires, or {@link Long#MAX_VALUE} for never
 */
public record Mutation(String key, String value, long deadlineMs) {
}
