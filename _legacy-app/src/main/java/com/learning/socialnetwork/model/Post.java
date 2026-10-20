package com.learning.socialnetwork.model;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public class Post {
    private final String postId;
    private final String userId;
    private final String content;
    private final Instant createdAt;
    private final AtomicInteger likes;

    public Post(String postId, String userId, String content) {
        this.postId = postId;
        this.userId = userId;
        this.content = content;
        this.createdAt = Instant.now();
        this.likes = new AtomicInteger(0);
    }

    public void like() {
        likes.incrementAndGet();
    }

    public String getPostId() { return postId; }
    public String getUserId() { return userId; }
    public String getContent() { return content; }
    public int getLikes() { return likes.get(); }
    public Instant getCreatedAt() { return createdAt; }
}
