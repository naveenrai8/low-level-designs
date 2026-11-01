package com.lld.pubsub;

/**
 * One handing-out of a message to a consumer. It is the receipt you give back to ack or nack.
 *
 * @param message the message being delivered
 * @param attempt 1 for the first delivery, 2 for the first redelivery, and so on
 * @param token   unique per delivery, so an old receipt can never ack a newer delivery of the same message
 */
public record Delivery(Message message, int attempt, long token) {
}
