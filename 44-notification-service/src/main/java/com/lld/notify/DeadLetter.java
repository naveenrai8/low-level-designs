package com.lld.notify;

/**
 * One entry of the dead letter queue (DLQ): a message we gave up on, kept so a person can look at it and decide.
 *
 * @param id the notification id
 * @param reason why we gave up
 * @param attempts how many channel calls were made
 */
public record DeadLetter(String id, String reason, int attempts) {
}
