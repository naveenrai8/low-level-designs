package com.learning.logging.core;

import java.time.Instant;

public record LogMessage(
    Instant timestamp,
    LogLevel level,
    String loggerName,
    String message,
    String threadName,
    Throwable throwable
) {
    public static LogMessage create(LogLevel level, String loggerName, String message, Throwable throwable) {
        return new LogMessage(
            Instant.now(),
            level,
            loggerName,
            message,
            Thread.currentThread().getName(),
            throwable
        );
    }

    @Override
    public String toString() {
        String formatted = String.format("%s [%s] [%s] %s - %s",
            timestamp, threadName, level, loggerName, message);
        if (throwable != null) {
            formatted += "\n" + throwable.toString();
        }
        return formatted;
    }
}
