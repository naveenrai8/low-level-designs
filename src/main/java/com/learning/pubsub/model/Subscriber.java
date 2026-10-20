package com.learning.pubsub.model;

import java.util.concurrent.ConcurrentLinkedQueue;

public class Subscriber {
    private final String subscriberId;
    private final String topic;
    private final ConcurrentLinkedQueue<Message> messageQueue;

    public Subscriber(String subscriberId, String topic) {
        this.subscriberId = subscriberId;
        this.topic = topic;
        this.messageQueue = new ConcurrentLinkedQueue<>();
    }

    public void receiveMessage(Message message) {
        messageQueue.offer(message);
    }

    public Message pollMessage() {
        return messageQueue.poll();
    }

    public int getQueueSize() {
        return messageQueue.size();
    }

    public String getSubscriberId() { return subscriberId; }
    public String getTopic() { return topic; }
}
