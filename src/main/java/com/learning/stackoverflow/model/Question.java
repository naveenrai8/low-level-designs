package com.learning.stackoverflow.model;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class Question {
    private final Long id;
    private final String title;
    private String body;
    private final Long authorId;
    private final Set<String> tags;
    private final Instant createdAt;
    private Instant updatedAt;
    private final AtomicInteger voteCount;
    private Long acceptedAnswerId;

    public Question(Long id, String title, String body, Long authorId, Set<String> tags) {
        this.id = id;
        this.title = title;
        this.body = body;
        this.authorId = authorId;
        this.tags = tags;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.voteCount = new AtomicInteger(0);
    }

    public void incrementVotes() { voteCount.incrementAndGet(); }
    public void decrementVotes() { voteCount.decrementAndGet(); }
    public void setAcceptedAnswer(Long answerId) { this.acceptedAnswerId = answerId; }
    public void updateBody(String newBody) {
        this.body = newBody;
        this.updatedAt = Instant.now();
    }

    // Getters
    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public Long getAuthorId() { return authorId; }
    public Set<String> getTags() { return tags; }
    public Instant getCreatedAt() { return createdAt; }
    public int getVoteCount() { return voteCount.get(); }
    public Long getAcceptedAnswerId() { return acceptedAnswerId; }
}
