package com.learning.logging.core;

public enum LogLevel {
    DEBUG(0),
    INFO(1),
    WARN(2),
    ERROR(3),
    FATAL(4);

    private final int priority;

    LogLevel(int priority) {
        this.priority = priority;
    }

    public int getPriority() {
        return priority;
    }

    public boolean isEnabled(LogLevel configuredLevel) {
        return this.priority >= configuredLevel.priority;
    }
}
