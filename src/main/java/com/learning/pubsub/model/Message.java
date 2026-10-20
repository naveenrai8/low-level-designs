package com.learning.pubsub.model;

import java.time.Instant;
import java.util.UUID;

public class Message {
    private final String messageId;
    private final String topic;
    private final String content;
    private final Instant timestamp;

    public Message(String topic, String content) {
        this.messageId = UUID.randomUUID().toString();
        this.topic = topic;
        this.content = content;
        this.timestamp = Instant.now();
    }

    public String getMessageId() { return messageId; }
    public String getTopic() { return topic; }
    public String getContent() { return content; }
    public Instant getTimestamp() { return timestamp; }
}
