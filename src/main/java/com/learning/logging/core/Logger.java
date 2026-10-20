package com.learning.logging.core;

import com.learning.logging.appender.LogAppender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class Logger {
    private final String name;
    private volatile LogLevel level;
    private final List<LogAppender> appenders;

    public Logger(String name, LogLevel level) {
        this.name = name;
        this.level = level;
        this.appenders = new CopyOnWriteArrayList<>();
    }

    public void addAppender(LogAppender appender) {
        appenders.add(appender);
    }

    public void debug(String message) {
        log(LogLevel.DEBUG, message, null);
    }

    public void info(String message) {
        log(LogLevel.INFO, message, null);
    }

    public void warn(String message) {
        log(LogLevel.WARN, message, null);
    }

    public void error(String message) {
        log(LogLevel.ERROR, message, null);
    }

    public void error(String message, Throwable throwable) {
        log(LogLevel.ERROR, message, throwable);
    }

    public void fatal(String message) {
        log(LogLevel.FATAL, message, null);
    }

    private void log(LogLevel level, String message, Throwable throwable) {
        if (!level.isEnabled(this.level)) {
            return;
        }

        LogMessage logMessage = LogMessage.create(level, name, message, throwable);
        for (LogAppender appender : appenders) {
            appender.append(logMessage);
        }
    }

    public void setLevel(LogLevel level) {
        this.level = level;
    }
}
