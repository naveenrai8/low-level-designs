package com.lld.cache;

/** One removal event, handed to the listener after the cache lock is released. */
public record Removal<K, V>(K key, V value, RemovalCause cause) {
}
