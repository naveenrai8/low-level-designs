package com.learning.logging.config;

import com.learning.logging.appender.ConsoleAppender;
import com.learning.logging.core.LogLevel;
import com.learning.logging.core.Logger;
import java.util.concurrent.ConcurrentHashMap;

public class LoggerFactory {
    private static final ConcurrentHashMap<String, Logger> loggers = new ConcurrentHashMap<>();
    private static final LogLevel defaultLevel = LogLevel.INFO;

    public static Logger getLogger(String name) {
        return loggers.computeIfAbsent(name, n -> {
            Logger logger = new Logger(n, defaultLevel);
            logger.addAppender(new ConsoleAppender());
            return logger;
        });
    }

    public static Logger getLogger(Class<?> clazz) {
        return getLogger(clazz.getName());
    }
}
