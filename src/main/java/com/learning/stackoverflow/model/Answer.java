package com.learning.stackoverflow.model;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public class Answer {
    private final Long id;
    private final Long questionId;
    private String body;
    private final Long authorId;
    private final Instant createdAt;
    private final AtomicInteger voteCount;
    private boolean accepted;

    public Answer(Long id, Long questionId, String body, Long authorId) {
        this.id = id;
        this.questionId = questionId;
        this.body = body;
        this.authorId = authorId;
        this.createdAt = Instant.now();
        this.voteCount = new AtomicInteger(0);
        this.accepted = false;
    }

    public void incrementVotes() { voteCount.incrementAndGet(); }
    public void decrementVotes() { voteCount.decrementAndGet(); }
    public void markAccepted() { this.accepted = true; }

    public Long getId() { return id; }
    public Long getQuestionId() { return questionId; }
    public String getBody() { return body; }
    public Long getAuthorId() { return authorId; }
    public Instant getCreatedAt() { return createdAt; }
    public int getVoteCount() { return voteCount.get(); }
    public boolean isAccepted() { return accepted; }
}
