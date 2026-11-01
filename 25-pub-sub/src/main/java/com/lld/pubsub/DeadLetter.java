package com.lld.pubsub;

/**
 * A message that failed too many times and was parked in the dead-letter queue (DLQ) for a human or a repair job.
 *
 * @param message  the message that could not be processed
 * @param attempts how many times it was delivered before giving up, always equal to the configured maximum
 * @param reason   "nack" or "ack deadline expired", describing the last failure
 */
public record DeadLetter(Message message, int attempts, String reason) {
}
