package com.lld.pubsub;

import java.util.List;
import java.util.Optional;

/**
 * An in-process publish/subscribe broker. A topic is an append-only log; each subscriber group keeps its own read position (offset) on it.
 *
 * <p>Error contract: unknown topic or group throws {@link IllegalArgumentException}; subscribing the same group twice throws {@link IllegalStateException}; publishing to a topic with a lagging group throws {@link BackpressureException}. {@code ack} and {@code nack} return false (never throw) when the receipt is stale, for example after the ack deadline passed.
 *
 * <p>Delivery is at-least-once: a message may be delivered again after a nack or a missed deadline, so handlers must be idempotent.
 */
public interface PubSub {

    /**
     * Creates a topic with no lag limit.
     *
     * @param topic the topic name
     */
    void createTopic(String topic);

    /**
     * Creates a topic whose publishers are told to slow down once any group has {@code maxLag} or more unfinished messages.
     *
     * @param topic  the topic name
     * @param maxLag the lag at which publish starts failing; must be at least 1
     */
    void createTopic(String topic, int maxLag);

    /**
     * Adds a subscriber group to a topic. Several consumers can share one group; they split its messages.
     *
     * @param topic  the topic name
     * @param group  the group name, unique within the topic
     * @param config the group's retry, deadline and start settings
     */
    void subscribe(String topic, String group, SubscriptionConfig config);

    /**
     * Appends a message to the topic log.
     *
     * @param topic   the topic name
     * @param key     the ordering key
     * @param payload the body
     * @return the new message's offset
     * @throws BackpressureException if a group is at or over the topic's lag limit; nothing is appended
     */
    long publish(String topic, String key, String payload);

    /**
     * Hands the group its next deliverable message without waiting.
     *
     * @param topic the topic name
     * @param group the group name
     * @return the delivery, or empty when nothing is deliverable right now (all remaining messages may be blocked behind an in-flight message of the same key)
     */
    Optional<Delivery> poll(String topic, String group);

    /**
     * Like {@link #poll} but waits until a message is deliverable.
     *
     * @param topic the topic name
     * @param group the group name
     * @return the next delivery
     * @throws InterruptedException if the waiting thread is interrupted
     */
    Delivery take(String topic, String group) throws InterruptedException;

    /**
     * Confirms the message was processed, so it is never delivered again.
     *
     * @param topic    the topic name
     * @param group    the group name
     * @param delivery the receipt from poll or take
     * @return true if accepted; false if the receipt is stale (deadline passed, already settled, or the group was rewound)
     */
    boolean ack(String topic, String group, Delivery delivery);

    /**
     * Reports failure, so the message is redelivered, or dead-lettered if it used its last attempt.
     *
     * @param topic    the topic name
     * @param group    the group name
     * @param delivery the receipt from poll or take
     * @return true if accepted; false if the receipt is stale
     */
    boolean nack(String topic, String group, Delivery delivery);

    /**
     * Moves only this group's read position. Other groups are never affected. Anything pending or in flight for the group is forgotten, and old receipts become stale.
     *
     * @param topic  the topic name
     * @param group  the group name
     * @param offset the new next-to-read offset, from 0 to the log size
     */
    void seek(String topic, String group, long offset);

    /**
     * Counts the group's unfinished messages: unread, waiting for retry, or in flight.
     *
     * @param topic the topic name
     * @param group the group name
     * @return the lag
     */
    int lag(String topic, String group);

    /**
     * Returns the group's next-to-read offset in the log.
     *
     * @param topic the topic name
     * @param group the group name
     * @return the position
     */
    long position(String topic, String group);

    /**
     * Returns a snapshot of the group's dead-letter queue.
     *
     * @param topic the topic name
     * @param group the group name
     * @return the dead letters, oldest first
     */
    List<DeadLetter> deadLetters(String topic, String group);
}
