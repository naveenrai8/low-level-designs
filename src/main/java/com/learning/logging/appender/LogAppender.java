package com.learning.logging.appender;

import com.learning.logging.core.LogMessage;

public interface LogAppender {
    void append(LogMessage message);
    void flush();
    void close();
}
