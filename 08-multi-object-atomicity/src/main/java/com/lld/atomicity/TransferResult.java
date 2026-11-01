package com.lld.atomicity;

/**
 * The outcome of a transfer. An expected business outcome is a value, not an exception (Topic 04).
 */
public enum TransferResult {
    OK,
    INSUFFICIENT_FUNDS
}
