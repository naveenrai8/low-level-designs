package com.lld.pubsub;

/** Application code that processes one message. Returning normally means success (ack); throwing means failure (nack). */
@FunctionalInterface
public interface MessageHandler {

    /**
     * Processes one message.
     *
     * @param message the message to process
     * @throws Exception any failure; the pool turns it into a nack so the message is retried
     */
    void handle(Message message) throws Exception;
}
