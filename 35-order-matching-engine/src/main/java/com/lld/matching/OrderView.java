package com.lld.matching;

/**
 * A read-only picture of one order. The identity {@code original == filled + resting + cancelled} always holds.
 *
 * @param id        the order id
 * @param side      buy or sell
 * @param price     the current limit price in ticks (0 for a market order)
 * @param original  total quantity ever asked for (grows if a replace raises it)
 * @param filled    quantity traded so far
 * @param resting   quantity waiting in the book now
 * @param cancelled quantity removed without trading
 * @param seq       queue position stamp: a smaller number at the same price means earlier in line
 */
public record OrderView(String id, Side side, long price, long original, long filled, long resting, long cancelled, long seq) {
}
