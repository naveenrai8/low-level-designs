package com.learning.taskmanagement.model;

import java.time.Instant;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Task {
    private final Long id;
    private String title;
    private String description;
    private Instant dueDate;
    private Priority priority;
    private TaskStatus status;
    private Long assignedTo;
    private final Long createdBy;
    private final Instant createdAt;
    private Instant updatedAt;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public Task(Long id, String title, String description, Instant dueDate,
                Priority priority, Long createdBy) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.dueDate = dueDate;
        this.priority = priority;
        this.status = TaskStatus.PENDING;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void update(String title, String description, Instant dueDate, Priority priority) {
        lock.writeLock().lock();
        try {
            if (title != null) this.title = title;
            if (description != null) this.description = description;
            if (dueDate != null) this.dueDate = dueDate;
            if (priority != null) this.priority = priority;
            this.updatedAt = Instant.now();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void setStatus(TaskStatus status) {
        lock.writeLock().lock();
        try {
            this.status = status;
            this.updatedAt = Instant.now();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void assignTo(Long userId) {
        lock.writeLock().lock();
        try {
            this.assignedTo = userId;
            this.updatedAt = Instant.now();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Getters with read lock
    public Long getId() { return id; }
    public String getTitle() { return getWithReadLock(() -> title); }
    public String getDescription() { return getWithReadLock(() -> description); }
    public Instant getDueDate() { return dueDate; }
    public Priority getPriority() { return priority; }
    public TaskStatus getStatus() { return status; }
    public Long getAssignedTo() { return assignedTo; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }

    private <T> T getWithReadLock(java.util.function.Supplier<T> supplier) {
        lock.readLock().lock();
        try {
            return supplier.get();
        } finally {
            lock.readLock().unlock();
        }
    }
}
