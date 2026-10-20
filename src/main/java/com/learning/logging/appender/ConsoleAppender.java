package com.learning.logging.appender;

import com.learning.logging.core.LogMessage;

public class ConsoleAppender implements LogAppender {
    @Override
    public void append(LogMessage message) {
        System.out.println(message);
    }

    @Override
    public void flush() {
        System.out.flush();
    }

    @Override
    public void close() {
        // Nothing to close
    }
}
