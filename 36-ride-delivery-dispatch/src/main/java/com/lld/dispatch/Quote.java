package com.lld.dispatch;

/**
 * A price promised to a rider at request time. It is locked: later surge changes never alter it.
 *
 * @param requestId the request it belongs to
 * @param surgeBps the surge multiplier in basis points (10,000 = 1.00x)
 * @param amountMinor the price in minor units (cents)
 */
public record Quote(long requestId, int surgeBps, long amountMinor) {
}
