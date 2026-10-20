package com.learning.linkedin.model;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class Post {
    private final String postId;
    private final String userId;
    private final String content;
    private final Instant timestamp;
    private final AtomicInteger likes;

    public Post(String userId, String content) {
        this.postId = UUID.randomUUID().toString();
        this.userId = userId;
        this.content = content;
        this.timestamp = Instant.now();
        this.likes = new AtomicInteger(0);
    }

    public void like() {
        likes.incrementAndGet();
    }

    public String getPostId() { return postId; }
    public String getUserId() { return userId; }
    public String getContent() { return content; }
    public Instant getTimestamp() { return timestamp; }
    public int getLikes() { return likes.get(); }
}
