package com.learning.stackoverflow.model;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents a user in the Stack Overflow system.
 * Thread-safe implementation using AtomicInteger for reputation.
 */
public class User {
    private final Long id;
    private final String username;
    private final String email;
    private final AtomicInteger reputation;
    private final Instant createdAt;

    public User(Long id, String username, String email) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.reputation = new AtomicInteger(1); // Initial reputation
        this.createdAt = Instant.now();
    }

    /**
     * Adds reputation points (can be negative for downvotes).
     * Thread-safe operation.
     */
    public void addReputation(int points) {
        reputation.addAndGet(points);
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public int getReputation() {
        return reputation.get();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
